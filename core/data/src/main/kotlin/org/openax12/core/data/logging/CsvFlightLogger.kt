package org.openax12.core.data.logging

import android.net.Uri
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.FlightLogFile
import org.openax12.core.data.FlightLogState
import org.openax12.core.data.FlightLogger
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.data.library.ModelNames
import org.openax12.core.data.library.UriAccess
import org.openax12.core.data.storage.FileOps
import org.openax12.core.model.AppDispatchers
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.Sources
import org.openax12.core.model.SwitchPosition
import org.openax12.core.model.UnitConversion
import org.openax12.core.radio.Radio

/**
 * The CSV layout of a flight log, modelled on EdgeTX logs:
 * `Date,Time,<sensor>(<unit>)…,Rud,Ele,Thr,Ail,SA…SP,CH1(%)…CH16(%),FM,TxBat(V)`.
 * Sensor values are written in their native metric unit with the sensor's precision; a sensor
 * without a value, or one that is lost, leaves its cell empty. Switches are -1 / 0 / 1
 * (up / middle / down), sticks and channels are percent. Pure functions.
 */
internal object CsvLogFormat {

    const val EXTENSION: String = "csv"

    private val FILE_NAME = Regex("""^(.*)-(\d{4}-\d{2}-\d{2})-(\d{6})(?:-p(\d+))?\.csv$""")

    /** The sensors that get a column, in list order: everything except live values and the fixed columns. */
    fun sensorColumns(sensors: List<SensorInfo>): List<SensorInfo> =
        sensors.filter { !it.id.isLive && it.id != Sensors.TX_BATTERY && it.id != Sensors.FLIGHT_MODE }

    /** The header line (without line break). */
    fun header(sensors: List<SensorInfo>): String = buildString {
        append("Date,Time")
        val used = HashSet<String>()
        for (sensor in sensors) {
            val base = columnName(sensor)
            // Two sensors can share a name (a CRSF value and the SDK's own copy of it).
            var name = base
            var counter = 2
            while (!used.add(name)) name = "$base#${counter++}"
            append(',').append(escape(name))
        }
        for (index in Sources.STICKS) append(',').append(Sources.defaultName(index))
        for (index in Sources.SWITCHES) append(',').append(Sources.defaultName(index))
        for (channel in 1..CHANNEL_COUNT) append(",CH").append(channel).append("(%)")
        append(",FM,TxBat(V)")
    }

    /**
     * One data line (without line break).
     *
     * @param date already formatted date cell
     * @param time already formatted time cell
     * @param valueOf current value of a sensor, or null if it has none
     */
    fun row(
        date: String,
        time: String,
        sensors: List<SensorInfo>,
        valueOf: (SensorId) -> SensorValue?,
        live: LiveSnapshot,
        flightMode: String,
    ): String = buildString {
        append(date).append(',').append(time)
        for (sensor in sensors) {
            append(',')
            val value = valueOf(sensor.id) ?: continue
            if (value.lost) continue
            when {
                value.hasValue -> append(UnitConversion.formatNumber(value.value, value.precision))
                value.text != null -> append(escape(value.text.orEmpty()))
            }
        }
        for (index in Sources.STICKS) append(',').append(live.logicAt(index))
        for (index in Sources.SWITCHES) {
            append(',')
            append(
                when (SwitchPosition.fromLogic(live.logicAt(index))) {
                    SwitchPosition.UP -> -1
                    SwitchPosition.MID -> 0
                    SwitchPosition.DOWN -> 1
                },
            )
        }
        for (channel in 0 until CHANNEL_COUNT) append(',').append(live.channel(channel))
        append(',').append(escape(flightMode))
        append(',').append(UnitConversion.formatNumber(live.status.txVoltage.toDouble(), 2))
    }

    /** Column title of a sensor: its template name or display name, plus the unit in parentheses. */
    fun columnName(sensor: SensorInfo): String {
        val name = (sensor.templateName ?: sensor.name).ifBlank { sensor.id.key }
        val unit = sensor.unit.symbol
        return if (unit.isEmpty() || sensor.unit == SensorUnit.TEXT) name else "$name($unit)"
    }

    /** Quotes a cell if it contains a separator, a quote or a line break (RFC 4180). */
    fun escape(cell: String): String {
        if (cell.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return cell
        return '"' + cell.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + '"'
    }

    /** `<model>-<yyyy-MM-dd>-<HHmmss>.csv`; later parts of the same flight get `-p2`, `-p3`, … */
    fun fileName(modelName: String, dateAndTime: String, part: Int = 1): String {
        val model = ModelNames.sanitize(modelName, fallback = "Model").replace(' ', '_')
        return if (part <= 1) "$model-$dateAndTime.$EXTENSION" else "$model-$dateAndTime-p$part.$EXTENSION"
    }

    /** Model name and `yyyy-MM-dd-HHmmss` stamp of a log file name; null if it is not one of ours. */
    fun parseFileName(name: String): Pair<String, String>? {
        val match = FILE_NAME.matchEntire(name) ?: return null
        return match.groupValues[1].replace('_', ' ') to "${match.groupValues[2]}-${match.groupValues[3]}"
    }
}

/**
 * [FlightLogger] writing CSV files into `<library root>/logs/`.
 *
 * The columns of a file are fixed when it is created. Sensors that only show up later (a GPS
 * that gets its fix, a flight controller that starts talking) make the logger continue in a new
 * file part (`…-p2.csv`), so every file stays a plain rectangular CSV.
 *
 * @param modelName name of the current model, read when a log starts
 * @param logsDir the log directory right now; null if the library is unavailable
 * @param intervalMs the configured sample interval, read before every row
 */
internal class CsvFlightLogger(
    private val radio: Radio,
    private val telemetry: TelemetryRepository,
    private val modelName: () -> String,
    private val logsDir: () -> File?,
    private val intervalMs: () -> Int,
    private val uriAccess: UriAccess,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : FlightLogger {

    private val _state = MutableStateFlow<FlightLogState>(FlightLogState.Idle)
    override val state: StateFlow<FlightLogState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null

    override suspend fun start(): DataResult<FlightLogFile> = withContext(dispatchers.io) {
        mutex.withLock {
            (_state.value as? FlightLogState.Recording)?.let { return@withLock DataResult.Success(it.file) }
            val dir = logsDir() ?: return@withLock fail(DataError.IO, "the log folder is not available")
            val startedMs = wallClock()
            val session = try {
                FileOps.ensureDir(dir)
                if (dir.usableSpace in 1 until MIN_FREE_BYTES) throw IOException("storage is full")
                Session(dir, modelName().ifBlank { "Model" }, startedMs)
            } catch (e: IOException) {
                return@withLock fail(DataError.IO, e.message.orEmpty())
            }
            val file = session.logFile()
            _state.value = FlightLogState.Recording(file, startedMs)
            job = scope.launch(dispatchers.io) { record(session) }
            DataResult.Success(file)
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            job?.cancelAndJoin()
            job = null
            if (_state.value is FlightLogState.Recording) _state.value = FlightLogState.Idle
        }
    }

    override suspend fun logs(): List<FlightLogFile> = withContext(dispatchers.io) {
        val dir = logsDir() ?: return@withContext emptyList()
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".${CsvLogFormat.EXTENSION}", ignoreCase = true) }
            .map(::describe)
            .sortedWith(compareByDescending<FlightLogFile> { it.startedMs }.thenByDescending { it.name })
    }

    override suspend fun delete(log: FlightLogFile): DataResult<Unit> = withContext(dispatchers.io) {
        val file = resolve(log) ?: return@withContext DataResult.Failure(DataError.NOT_FOUND, log.name)
        val recording = (_state.value as? FlightLogState.Recording)?.file?.path
        if (recording != null && File(recording).absolutePath == file.absolutePath) {
            return@withContext DataResult.Failure(DataError.BUSY, "the log is being recorded")
        }
        if (file.delete()) DataResult.Success(Unit) else DataResult.Failure(DataError.IO, "cannot delete ${file.name}")
    }

    override suspend fun share(log: FlightLogFile): DataResult<Uri> = withContext(dispatchers.io) {
        val file = resolve(log) ?: return@withContext DataResult.Failure(DataError.NOT_FOUND, log.name)
        uriAccess.shareUri(file)?.let { return@withContext DataResult.Success(it) }
        // Not in a shareable location: hand out a copy from the share cache.
        try {
            val copy = File(FileOps.ensureDir(uriAccess.shareDir), file.name)
            FileOps.copyFile(file, copy)
            uriAccess.shareUri(copy)?.let { DataResult.Success(it) } ?: DataResult.Failure(DataError.IO, "the log cannot be shared")
        } catch (e: IOException) {
            DataResult.Failure(DataError.IO, e.message.orEmpty())
        }
    }

    // -- recording ------------------------------------------------------------------------------

    /** One flight: the open file part and its columns. */
    private inner class Session(private val dir: File, private val model: String, val startedMs: Long) {
        private val stamp: String = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date(startedMs))
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        private var part = 1
        var file: File = newFile()
            private set
        var columns: List<SensorInfo> = CsvLogFormat.sensorColumns(telemetry.sensorList.value)
            private set
        private var writer: BufferedWriter = open()
        var rows = 0L
            private set

        fun logFile(): FlightLogFile =
            FlightLogFile(name = file.name, path = file.absolutePath, modelName = model, startedMs = startedMs, sizeBytes = file.length())

        fun writeRow() {
            val now = Date(wallClock())
            writer.write(
                CsvLogFormat.row(
                    date = dateFormat.format(now),
                    time = timeFormat.format(now),
                    sensors = columns,
                    valueOf = telemetry::value,
                    live = radio.live.value,
                    flightMode = telemetry.value(Sensors.FLIGHT_MODE)?.text.orEmpty(),
                ),
            )
            writer.newLine()
            rows++
        }

        fun flush() = writer.flush()

        /** Continues in a new file part if sensors appeared that the current file has no column for. */
        fun rollOverIfNeeded(): Boolean {
            if (part >= MAX_PARTS) return false
            val current = CsvLogFormat.sensorColumns(telemetry.sensorList.value)
            val known = columns.mapTo(HashSet()) { it.id }
            if (current.all { it.id in known }) return false
            writer.close()
            part++
            file = newFile()
            columns = current
            writer = open()
            return true
        }

        fun close() {
            try {
                writer.close()
            } catch (_: IOException) {
                // Nothing left to do with a file that cannot be closed.
            }
        }

        fun freeSpaceLow(): Boolean = dir.usableSpace in 1 until MIN_FREE_BYTES

        private fun newFile(): File {
            var candidate = File(dir, CsvLogFormat.fileName(model, stamp, part))
            // Two logs started within the same second.
            while (candidate.exists() && part < MAX_PARTS * 2) {
                part++
                candidate = File(dir, CsvLogFormat.fileName(model, stamp, part))
            }
            return candidate
        }

        private fun open(): BufferedWriter {
            val out = file.bufferedWriter(Charsets.UTF_8)
            out.write(CsvLogFormat.header(columns))
            out.newLine()
            return out
        }
    }

    private suspend fun record(session: Session) {
        var lastFlushMs = wallClock()
        var lastColumnCheckMs = lastFlushMs
        try {
            while (true) {
                delay(intervalMs().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS).toLong())
                session.writeRow()
                val now = wallClock()
                if (now - lastColumnCheckMs >= COLUMN_CHECK_MS || now < lastColumnCheckMs) {
                    lastColumnCheckMs = now
                    session.rollOverIfNeeded()
                }
                if (now - lastFlushMs >= FLUSH_MS || now < lastFlushMs) {
                    lastFlushMs = now
                    session.flush()
                    if (session.freeSpaceLow()) throw IOException("storage is full")
                    _state.value = FlightLogState.Recording(session.logFile(), session.startedMs, session.rows)
                }
            }
        } catch (e: IOException) {
            _state.value = FlightLogState.Failed(DataError.IO, e.message.orEmpty())
        } finally {
            session.close()
        }
    }

    private fun describe(file: File): FlightLogFile {
        val parsed = CsvLogFormat.parseFileName(file.name)
        val started = parsed?.second?.let { stamp ->
            runCatching { SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).parse(stamp)?.time }.getOrNull()
        }
        return FlightLogFile(
            name = file.name,
            path = file.absolutePath,
            modelName = parsed?.first.orEmpty(),
            startedMs = started ?: file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    /** The log file behind [log], if it really is a file inside the log directory. */
    private fun resolve(log: FlightLogFile): File? {
        val dir = logsDir() ?: return null
        val file = File(log.path)
        return file.takeIf { it.isFile && it.parentFile != null && FileOps.isInside(dir, it) }
    }

    private fun fail(error: DataError, detail: String): DataResult.Failure {
        _state.value = FlightLogState.Failed(error, detail)
        return DataResult.Failure(error, detail)
    }

    companion object {
        private const val MIN_INTERVAL_MS = 50
        private const val MAX_INTERVAL_MS = 60_000
        private const val FLUSH_MS = 1000L
        private const val COLUMN_CHECK_MS = 5000L
        private const val MAX_PARTS = 20
        private const val MIN_FREE_BYTES = 8L * 1024 * 1024
    }
}
