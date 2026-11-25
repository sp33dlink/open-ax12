package org.openax12.feature.system.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openax12.core.audio.TtsStatus
import org.openax12.core.data.CrsfStats
import org.openax12.core.data.StorageInfo
import org.openax12.core.model.CurveType
import org.openax12.core.model.FirmwareInfo
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.RadioBackend
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TimerConfig
import org.openax12.core.radio.ConnectionState
import org.openax12.core.runtime.DiagnosticEntry
import org.openax12.core.runtime.StartupState
import org.openax12.feature.system.common.hexType

/** One input / output pair of the curve probe. */
internal data class CurveProbeRow(val input: Int, val device: Double, val expected: Double)

/**
 * `Radio.applyCurve` against the app's own `CurveMath` for one curve (assumptions 12 and 19).
 * [expected] is what the percent-based model predicts.
 */
internal data class CurveProbe(val curveIndex: Int, val type: CurveType, val rows: List<CurveProbeRow>) {
    /** Largest difference between the device and the model over all rows. */
    val maxDeviation: Double get() = rows.maxOfOrNull { kotlin.math.abs(it.device - it.expected) } ?: 0.0

    /** The device output looks like half of the model's (the raw half-scale unit). */
    val looksHalfScale: Boolean
        get() = rows.any { kotlin.math.abs(it.expected) > 10.0 } &&
            rows.all { kotlin.math.abs(it.device - it.expected / 2.0) <= PROBE_TOLERANCE }

    /** The device output matches the percent model. */
    val matchesPercent: Boolean get() = rows.isNotEmpty() && maxDeviation <= PROBE_TOLERANCE

    companion object {
        /** Inputs the probe evaluates. */
        val INPUTS: List<Int> = listOf(-100, -50, 0, 50, 100)

        /** Difference in percent still counted as equal. */
        const val PROBE_TOLERANCE: Double = 2.0
    }
}

/** What the ELRS menu of the TX module delivered during a probe (assumption 13). */
internal data class ElrsProbe(
    val deviceName: String,
    val itemCount: Int,
    val typeCounts: Map<Int, Int>,
)

/** Everything one diagnostics refresh knows. Plain data: the screen and the export render it. */
internal data class DiagnosticsData(
    val capturedMs: Long = 0,
    val sdkVersion: String = "",
    val isSimulator: Boolean = false,
    val backend: RadioBackend = RadioBackend.SDK,
    val startup: StartupState = StartupState.NotStarted,
    val connection: ConnectionState = ConnectionState.Stopped,
    val firmware: FirmwareInfo? = null,
    val system: SystemConfig? = null,
    val status: RadioStatus = RadioStatus.EMPTY,
    val sources: List<SourceInfo> = emptyList(),
    val channels: List<OutputChannelConfig> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    /** Raw `rcGetCurrentModelCfgIndex`, null if the SDK reported none. */
    val currentModelRaw: Int? = null,
    /** The model `Radio.getCurrentModel()` resolved. */
    val currentModel: ModelInfo? = null,
    val timers: List<TimerConfig> = emptyList(),
    val counters: List<TimerConfig> = emptyList(),
    /** Slot reported by `findFreeDualRateSlot` per channel (null = none), by channel index. */
    val freeDualRateSlots: List<Int?> = emptyList(),
    /** Number of enabled dual-rate slots per channel, by channel index. */
    val enabledDualRateSlots: List<Int> = emptyList(),
    val curveProbe: CurveProbe? = null,
    val elrs: ElrsProbe? = null,
    val crsf: CrsfStats = CrsfStats(),
    val tts: TtsStatus = TtsStatus.Initializing,
    val storage: StorageInfo = StorageInfo(),
    /** Logic values seen per switch source index since the screen opened. */
    val observedSwitches: Map<Int, Set<Int>> = emptyMap(),
    val log: List<DiagnosticEntry> = emptyList(),
)

/** How a check came out. */
internal enum class Verdict {
    /** The readout agrees with the assumption. */
    OK,

    /** The readout contradicts the assumption or looks wrong. */
    SUSPICIOUS,

    /** The readout is data to compare by hand (with the stock app, with a datasheet). */
    INFO,

    /** Nothing to read yet: the radio is not connected or the probe was not run. */
    NO_DATA,

    /** Cannot be read from the app; the check needs a manual procedure. */
    MANUAL,
}

/** Buttons a check can offer. */
internal enum class CheckAction { CURVE_PROBE, ELRS_PROBE, TEST_VARIO, TEST_BEEPS, TEST_HAPTIC }

/**
 * One line of the assumptions list (`docs/ASSUMPTIONS.md`).
 *
 * @property number the assumption number
 * @property lines technical readout (key=value), not localized: it is the same text the export contains
 */
internal data class AssumptionCheck(
    val number: Int,
    val verdict: Verdict,
    val lines: List<String> = emptyList(),
    val actions: List<CheckAction> = emptyList(),
)

/** The assumption checks, computed from a [DiagnosticsData]. Pure. */
internal object AssumptionChecks {

    /** Number of assumptions listed (`docs/ASSUMPTIONS.md`). */
    const val COUNT: Int = 28

    private val SWITCH_VALUES = setOf(-100, 0, 100)

    /** All checks, in assumption order. */
    fun evaluate(data: DiagnosticsData): List<AssumptionCheck> = (1..COUNT).map { check(it, data) }

    private fun check(number: Int, d: DiagnosticsData): AssumptionCheck = when (number) {
        1 -> serialPort(d, number)
        2 -> if (d.connection.isReady) AssumptionCheck(number, Verdict.OK, listOf("connection=Ready: the SDK opened the port")) else AssumptionCheck(number, Verdict.NO_DATA, listOf("connection=${connectionName(d.connection)}"))
        3 -> switchValues(d, number)
        6 -> mixCapacity(d, number)
        7 -> crsfFrames(d, number)
        9 -> tts(d, number)
        10 -> AssumptionCheck(
            number,
            Verdict.INFO,
            listOf(
                "libraryRoot=${d.storage.libraryRoot}",
                "usesPublicStorage=${d.storage.usesPublicStorage}",
                "permissionGranted=${d.storage.permissionGranted}",
            ),
        )
        11 -> channelScale(d, number)
        12 -> curve(d, number, parametricOnly = true)
        13 -> elrsTypes(d, number)
        14 -> channelLocks(d, number)
        15 -> modelIndex(d, number)
        16 -> indexing(d, number)
        18 -> dualRateSlots(d, number)
        19 -> curve(d, number, parametricOnly = false)
        21, 23 -> timers(d, number)
        24 -> AssumptionCheck(number, if (d.connection is ConnectionState.Error) Verdict.SUSPICIOUS else Verdict.NO_DATA, errorLines(d.connection))
        25 -> rawEnums(d, number)
        26 -> AssumptionCheck(number, Verdict.MANUAL, listOf("turn the radio volume while the tone plays"), listOf(CheckAction.TEST_VARIO))
        27 -> AssumptionCheck(number, Verdict.MANUAL, listOf("listen for crackle and delay"), listOf(CheckAction.TEST_BEEPS))
        28 -> AssumptionCheck(number, Verdict.MANUAL, listOf("feel for vibration"), listOf(CheckAction.TEST_HAPTIC))
        else -> AssumptionCheck(number, Verdict.MANUAL)
    }

    private fun connectionName(c: ConnectionState): String = when (c) {
        ConnectionState.Stopped -> "Stopped"
        ConnectionState.Initializing -> "Initializing"
        ConnectionState.WaitingForDevice -> "WaitingForDevice"
        ConnectionState.Ready -> "Ready"
        is ConnectionState.Error -> "Error(${c.reason::class.simpleName})"
    }

    private fun errorLines(c: ConnectionState): List<String> = when (c) {
        is ConnectionState.Error -> listOf(
            "error=${c.reason::class.simpleName}",
            "message=${c.reason.message}",
            "mayBeStockAppConflict=${c.reason.mayBeStockAppConflict}",
        )
        else -> listOf("connection=${connectionName(c)}")
    }

    private fun serialPort(d: DiagnosticsData, number: Int): AssumptionCheck = when (val c = d.connection) {
        ConnectionState.Ready -> AssumptionCheck(number, Verdict.OK, listOf("connection=Ready (the port was free)"))
        is ConnectionState.Error -> AssumptionCheck(number, Verdict.SUSPICIOUS, errorLines(c))
        else -> AssumptionCheck(number, Verdict.NO_DATA, listOf("connection=${connectionName(c)}"))
    }

    private fun switchValues(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.observedSwitches.isEmpty()) {
            return AssumptionCheck(number, Verdict.NO_DATA, listOf("no switch data yet: move every switch through all positions"))
        }
        val lines = d.observedSwitches.toSortedMap().map { (index, values) ->
            "${sourceName(d, index)}=${values.sorted().joinToString(",")}"
        }
        val odd = d.observedSwitches.values.any { values -> values.any { it !in SWITCH_VALUES } }
        return AssumptionCheck(number, if (odd) Verdict.SUSPICIOUS else Verdict.OK, lines)
    }

    private fun sourceName(d: DiagnosticsData, index: Int): String =
        d.sources.firstOrNull { it.index == index }?.name?.takeIf { it.isNotBlank() } ?: org.openax12.core.model.Sources.defaultName(index)

    private fun mixCapacity(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.channels.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        val max = d.channels.maxOf { it.mixCount }
        val busy = d.channels.filter { it.mixCount > 0 }.joinToString(" ") { "CH${it.channel + 1}=${it.mixCount}" }
        return AssumptionCheck(number, Verdict.INFO, listOf("maxMixCountSeen=$max", busy.ifEmpty { "no mixes" }))
    }

    private fun crsfFrames(d: DiagnosticsData, number: Int): AssumptionCheck {
        val c = d.crsf
        val lines = listOf(
            "totalFrames=${c.totalFrames} crcErrors=${c.crcErrors} crcCheckDisabled=${c.crcCheckDisabled}",
            "vario(0x07)=${c.framesByType[0x07] ?: 0} baro(0x09)=${c.framesByType[0x09] ?: 0}",
        )
        val verdict = when {
            c.totalFrames == 0L -> Verdict.NO_DATA
            (c.framesByType[0x07] ?: 0L) > 0 && (c.framesByType[0x09] ?: 0L) > 0 -> Verdict.OK
            else -> Verdict.INFO
        }
        return AssumptionCheck(number, verdict, lines)
    }

    private fun tts(d: DiagnosticsData, number: Int): AssumptionCheck = when (val t = d.tts) {
        is TtsStatus.Ready -> AssumptionCheck(number, Verdict.OK, listOf("engine=${t.engine}", "language=${t.language}", "requestedAvailable=${t.requestedLanguageAvailable}"))
        TtsStatus.NoEngine -> AssumptionCheck(number, Verdict.SUSPICIOUS, listOf("no text-to-speech engine installed"))
        is TtsStatus.LanguageMissing -> AssumptionCheck(number, Verdict.SUSPICIOUS, listOf("engine=${t.engine}", "language missing: ${t.language}"))
        is TtsStatus.Error -> AssumptionCheck(number, Verdict.SUSPICIOUS, listOf("error=${t.message}"))
        TtsStatus.Initializing -> AssumptionCheck(number, Verdict.NO_DATA, listOf("engine is starting"))
    }

    private fun channelScale(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.channels.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        val outputs = d.channels.map { it.output }
        val inRange = outputs.all { it in -100..100 }
        return AssumptionCheck(
            number,
            if (d.channels.size == 16 && inRange) Verdict.OK else Verdict.SUSPICIOUS,
            listOf("channels=${d.channels.size}", "output min=${outputs.min()} max=${outputs.max()}"),
        )
    }

    private fun curve(d: DiagnosticsData, number: Int, parametricOnly: Boolean): AssumptionCheck {
        val probe = d.curveProbe ?: return AssumptionCheck(number, Verdict.NO_DATA, listOf("run the curve probe"), listOf(CheckAction.CURVE_PROBE))
        val lines = listOf("curve=${probe.curveIndex + 1} type=${probe.type}") +
            probe.rows.map { "in=${it.input} device=${fmt(it.device)} model=${fmt(it.expected)}" }
        val parametric = probe.type == CurveType.DIFF || probe.type == CurveType.EXPO || probe.type == CurveType.FUNC
        val verdict = when {
            parametricOnly && !parametric -> Verdict.INFO
            probe.type == CurveType.NONE -> Verdict.INFO
            probe.matchesPercent -> Verdict.OK
            else -> Verdict.SUSPICIOUS
        }
        val extra = if (!parametricOnly && probe.looksHalfScale) listOf("device output looks half-scale") else emptyList()
        return AssumptionCheck(number, verdict, lines + extra, listOf(CheckAction.CURVE_PROBE))
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun elrsTypes(d: DiagnosticsData, number: Int): AssumptionCheck {
        val probe = d.elrs ?: return AssumptionCheck(number, Verdict.NO_DATA, listOf("read the ELRS menu"), listOf(CheckAction.ELRS_PROBE))
        val lines = listOf("device=${probe.deviceName} items=${probe.itemCount}") +
            probe.typeCounts.toSortedMap().map { (type, count) -> "type $type x$count" }
        val known = setOf(0, 1, 2, 3, 8, 9, 10, 11, 12, 13)
        val verdict = when {
            probe.itemCount == 0 -> Verdict.NO_DATA
            probe.typeCounts.keys.all { it in known } && (11 in probe.typeCounts || 13 in probe.typeCounts || 9 in probe.typeCounts) -> Verdict.OK
            else -> Verdict.SUSPICIOUS
        }
        return AssumptionCheck(number, verdict, lines, listOf(CheckAction.ELRS_PROBE))
    }

    private fun channelLocks(d: DiagnosticsData, number: Int): AssumptionCheck {
        val locked = d.channels.filter { it.hasLock }
        if (d.channels.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        if (locked.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA, listOf("no channel lock configured"))
        return AssumptionCheck(
            number,
            Verdict.INFO,
            locked.map { "CH${it.channel + 1} lock src=${it.lock.source.index} op=${it.lock.op.sdk} value=${it.lock.value} locked=${it.lockValue}" },
        )
    }

    private fun modelIndex(d: DiagnosticsData, number: Int): AssumptionCheck {
        val raw = d.currentModelRaw ?: return AssumptionCheck(number, Verdict.NO_DATA, listOf("rcGetCurrentModelCfgIndex reported none"))
        val byIndex = d.models.any { it.index == raw.toLong() }
        val byPosition = d.models.getOrNull(raw) != null
        val lines = buildList {
            add("raw=$raw models=${d.models.size}")
            d.models.forEachIndexed { position, m -> add("#$position modelIdx=${m.index} name=${m.name}") }
            add("matchesModelIdx=$byIndex matchesPosition=$byPosition")
            add("resolved=${d.currentModel?.name ?: "none"}")
        }
        val verdict = when {
            d.models.size < 2 -> Verdict.INFO
            byIndex != byPosition -> Verdict.OK
            else -> Verdict.SUSPICIOUS
        }
        return AssumptionCheck(number, verdict, lines)
    }

    private fun indexing(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.sources.isEmpty() && d.channels.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        val sticks = d.sources.filter { it.index in 0..3 }.joinToString(" ") { "${it.name}: raw=${it.raw} logic=${it.logic} out=${it.output}" }
        return AssumptionCheck(
            number,
            Verdict.INFO,
            listOf(
                "sources=${d.sources.size} firstSourceIndex=${d.sources.firstOrNull()?.index}",
                "channels=${d.channels.size} firstChannelIndex=${d.channels.firstOrNull()?.channel}",
                sticks.ifEmpty { "no stick data" },
            ),
        )
    }

    private fun dualRateSlots(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.freeDualRateSlots.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        val lines = d.freeDualRateSlots.mapIndexed { ch, slot ->
            "CH${ch + 1} enabledSlots=${d.enabledDualRateSlots.getOrElse(ch) { 0 }} findFree=${slot ?: "none"}"
        }
        return AssumptionCheck(number, Verdict.INFO, lines)
    }

    private fun timers(d: DiagnosticsData, number: Int): AssumptionCheck {
        if (d.timers.isEmpty() && d.counters.isEmpty()) return AssumptionCheck(number, Verdict.NO_DATA)
        fun line(prefix: String, t: TimerConfig) =
            "$prefix${t.index + 1} mode=${t.mode} loop=${t.loopMode} out=${t.outputMode} start=${t.startTime} " +
                "countUp=${t.countUp} alert=${t.alertMode.sdk} sw=${t.switch.source.index}/${t.switch.op.sdk}/${t.switch.value} count=${t.count}"
        return AssumptionCheck(number, Verdict.INFO, d.timers.map { line("T", it) } + d.counters.map { line("CNT", it) })
    }

    private fun rawEnums(d: DiagnosticsData, number: Int): AssumptionCheck {
        val s = d.system ?: return AssumptionCheck(number, Verdict.NO_DATA)
        return AssumptionCheck(
            number,
            Verdict.INFO,
            listOf(
                "stickMode=${s.stickMode.sdk} (mapped; other device codes show as 2)",
                "rfModuleMode=${s.rfModuleMode.sdk} (mapped; 0xFF shows as 2)",
                "failsafeMode=${s.failsafeMode.sdk} crsfBaudRate=${s.crsfBaudRate}",
            ),
        )
    }
}

/** Builds the text of the diagnostics export. Pure; the screen shares it, the ViewModel saves it. */
internal object DiagnosticsReport {

    /** Name of CRSF frame types for the counter table. */
    fun crsfTypeName(type: Int): String = when (type) {
        0x02 -> "GPS"
        0x07 -> "Vario"
        0x08 -> "Battery"
        0x09 -> "Baro altitude"
        0x0A -> "Airspeed"
        0x14 -> "Link statistics"
        0x1E -> "Attitude"
        0x21 -> "Flight mode"
        0x88 -> "Rotorflight custom"
        else -> "unknown"
    }

    /** The full report. */
    fun build(d: DiagnosticsData, checks: List<AssumptionCheck>, appVersion: String): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(d.capturedMs))
        appendLine("open-ax12 diagnostics $time")
        appendLine("app=$appVersion sdk=${d.sdkVersion} simulator=${d.isSimulator} backend=${d.backend}")
        appendLine("startup=${d.startup}")
        appendLine("connection=${d.connection}")
        appendLine()
        appendLine("[firmware]")
        val fw = d.firmware
        if (fw == null || fw.isEmpty) {
            appendLine("not reported")
        } else {
            appendLine("device=${fw.deviceText} systemId=${fw.systemId} deviceId=${fw.deviceId}")
            appendLine("firmware=${fw.firmwareVersionText} packed=0x${fw.firmwareVersion.toString(16)} hardware=0x${fw.hardwareVersion.toString(16)}")
            appendLine("build=${fw.buildTimeText} date=${fw.buildDate} flightTimeSec=${fw.flightTimeSec}")
        }
        appendLine()
        appendLine("[system config]")
        val s = d.system
        if (s == null) {
            appendLine("not available")
        } else {
            appendLine("stickMode=${s.stickMode.sdk} rfModuleMode=${s.rfModuleMode.sdk} alarmVoltageDeciVolt=${s.alarmVoltageDeciVolt}")
            appendLine("failsafeMode=${s.failsafeMode.sdk} crsfBaudRate=${s.crsfBaudRate} stickBeep=${s.stickBeep} modelMatchId=${s.modelMatchId}")
            appendLine("preFlightCheck=${s.preFlightCheck.joinToString(",")}")
        }
        appendLine()
        appendLine("[radio status]")
        val st = d.status
        appendLine("lq=${st.linkQuality} rssiDbm=${st.rssiDbm} lostCounter=${st.lostCounter} txV=${st.txVoltage} rxV=${st.rxVoltage} topKey=${st.topKeyState} calibration=${st.calibrationState}")
        appendLine()
        appendLine("[sources] index name type enabled raw logic output weight")
        d.sources.forEach { appendLine("${it.index} ${it.name} ${it.type} ${it.enabled} ${it.raw} ${it.logic} ${it.output} ${it.weight}") }
        appendLine()
        appendLine("[channels] ch min max middle curve weight mixes output lock")
        d.channels.forEach {
            appendLine("${it.channel + 1} ${it.min} ${it.max} ${it.middle} ${it.curve} ${it.weight} ${it.mixCount} ${it.output} ${if (it.hasLock) "src${it.lock.source.index}/${it.lock.op.sdk}/${it.lock.value}=${it.lockValue}" else "-"}")
        }
        appendLine()
        appendLine("[crsf] total=${d.crsf.totalFrames} crcErrors=${d.crsf.crcErrors} crcCheckDisabled=${d.crsf.crcCheckDisabled} lastFrameAgeMs=${d.crsf.lastFrameAgeMs}")
        d.crsf.framesByType.toSortedMap().forEach { (type, count) -> appendLine("${hexType(type)} ${crsfTypeName(type)} $count") }
        if (d.crsf.unknownRotorflightSids.isNotEmpty()) appendLine("unknownRotorflightSids=${d.crsf.unknownRotorflightSids.sorted().joinToString(",") { "0x" + it.toString(16) }}")
        appendLine()
        appendLine("[assumption checks]")
        checks.forEach { check ->
            appendLine("#${check.number} ${check.verdict}")
            check.lines.forEach { appendLine("  $it") }
        }
        appendLine()
        appendLine("[log] newest last")
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        d.log.forEach { appendLine("${format.format(Date(it.timeMs))} ${it.source} ${it.message}") }
    }
}
