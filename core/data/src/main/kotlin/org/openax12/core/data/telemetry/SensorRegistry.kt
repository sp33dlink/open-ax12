package org.openax12.core.data.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.data.HistoryPoint
import org.openax12.core.data.TelemetryRepository
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorInfo
import org.openax12.core.model.SensorOverride
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue

/** Fixed-size ring of `(timestamp, value)` samples backed by primitive arrays. */
internal class HistoryBuffer(private val capacity: Int, private val minIntervalMs: Long) {

    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var start = 0
    private var size = 0

    /** Adds a sample unless the previous one is younger than the minimum interval. */
    fun add(timestampMs: Long, value: Double) {
        if (size > 0) {
            val last = times[(start + size - 1) % capacity]
            if (timestampMs - last < minIntervalMs) return
        }
        if (size < capacity) {
            times[(start + size) % capacity] = timestampMs
            values[(start + size) % capacity] = value
            size++
        } else {
            times[start] = timestampMs
            values[start] = value
            start = (start + 1) % capacity
        }
    }

    /** Samples not older than [sinceMs], oldest first. */
    fun since(sinceMs: Long): List<HistoryPoint> {
        val result = ArrayList<HistoryPoint>(size)
        for (i in 0 until size) {
            val index = (start + i) % capacity
            if (times[index] >= sinceMs) result += HistoryPoint(times[index], values[index])
        }
        return result
    }

    fun clear() {
        start = 0
        size = 0
    }
}

/**
 * The state behind `TelemetryRepository`: values, min / max, age and lost detection, per-model
 * overrides, history buffers and the cheap per-sensor flows. No Android and no coroutines of its
 * own; every time is passed in (monotonic milliseconds). Thread-safe.
 *
 * @param timeoutMs a [LostPolicy.LINK_OR_TIMEOUT] sensor is lost when it was not updated for this long
 * @param flowRefreshMs a per-sensor flow re-emits an otherwise unchanged value at most this often
 *   (so that the age shown by a widget stays roughly right)
 */
internal class SensorRegistry(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val historyCapacity: Int = TelemetryRepository.HISTORY_SECONDS * TelemetryRepository.HISTORY_HZ,
    private val historyMinIntervalMs: Long = 1000L / TelemetryRepository.HISTORY_HZ,
    private val flowRefreshMs: Long = 1000L,
) {

    private class Entry(var info: SensorInfo, val policy: LostPolicy) {
        var value = Double.NaN
        var text: String? = null
        var min = Double.NaN
        var max = Double.NaN
        var timestampMs = 0L
        var lost = true
        var seen = false
        var history: HistoryBuffer? = null
        var lastFlowEmitMs = Long.MIN_VALUE
    }

    private val lock = Any()
    private val entries = LinkedHashMap<SensorId, Entry>()
    private val flows = HashMap<SensorId, MutableStateFlow<SensorValue?>>()
    private var overrides: Map<String, SensorOverride> = emptyMap()
    private var linkLost = false
    private var valuesDirty = false
    private var listDirty = false

    /** True while the receiver link is marked lost (see [setLinkLost]). */
    val isLinkLost: Boolean get() = synchronized(lock) { linkLost }

    /** Makes [id] known (with its catalog defaults or [info]) without giving it a value. */
    fun register(id: SensorId, info: SensorInfo? = null, policy: LostPolicy? = null) {
        synchronized(lock) { entry(id, info, policy) }
    }

    /**
     * Stores a new sample of [id]. Unknown sensors are registered on the fly.
     *
     * @param value numeric value, or `NaN` for a text-only sample
     * @param info metadata to use if the sensor is new, or to replace the stored defaults with
     */
    fun update(id: SensorId, value: Double, nowMs: Long, text: String? = null, info: SensorInfo? = null, policy: LostPolicy? = null) {
        synchronized(lock) {
            val entry = entry(id, info, policy)
            if (info != null && info != entry.info) {
                entry.info = info
                listDirty = true
            }
            val before = if (flows.containsKey(id)) valueOf(entry) else null
            entry.value = value
            entry.text = text
            entry.timestampMs = nowMs
            entry.seen = true
            entry.lost = entry.policy != LostPolicy.NEVER && linkLost && entry.policy == LostPolicy.LINK
            if (!value.isNaN()) {
                if (entry.min.isNaN() || value < entry.min) entry.min = value
                if (entry.max.isNaN() || value > entry.max) entry.max = value
                val history = entry.history ?: HistoryBuffer(historyCapacity, historyMinIntervalMs).also { entry.history = it }
                history.add(nowMs, value)
            }
            valuesDirty = true
            emit(id, entry, before, nowMs)
        }
    }

    /** Marks the receiver link lost or recovered. Lost sensors keep their last value. */
    fun setLinkLost(lost: Boolean, nowMs: Long) {
        synchronized(lock) {
            if (linkLost == lost) return
            linkLost = lost
            for ((id, entry) in entries) {
                if (entry.policy == LostPolicy.NEVER || !entry.seen) continue
                val newLost = when {
                    lost -> true
                    entry.policy == LostPolicy.LINK -> false
                    else -> nowMs - entry.timestampMs > timeoutMs
                }
                if (newLost != entry.lost) {
                    val before = if (flows.containsKey(id)) valueOf(entry) else null
                    entry.lost = newLost
                    valuesDirty = true
                    emit(id, entry, before, nowMs)
                }
            }
        }
    }

    /** Marks sensors that stopped updating as lost. Returns true if any sensor changed. */
    fun checkTimeouts(nowMs: Long): Boolean {
        synchronized(lock) {
            var changed = false
            for ((id, entry) in entries) {
                if (entry.policy != LostPolicy.LINK_OR_TIMEOUT || entry.lost || !entry.seen) continue
                if (nowMs - entry.timestampMs > timeoutMs) {
                    val before = if (flows.containsKey(id)) valueOf(entry) else null
                    entry.lost = true
                    changed = true
                    emit(id, entry, before, nowMs)
                }
            }
            if (changed) valuesDirty = true
            return changed
        }
    }

    /** Resets min / max of [id] (all sensors when null) to the current value. */
    fun resetMinMax(id: SensorId?, nowMs: Long) {
        synchronized(lock) {
            for ((key, entry) in entries) {
                if (id != null && key != id) continue
                val before = if (flows.containsKey(key)) valueOf(entry) else null
                entry.min = entry.value
                entry.max = entry.value
                emit(key, entry, before, nowMs)
            }
            valuesDirty = true
        }
    }

    /** Drops all history samples. */
    fun clearHistory() {
        synchronized(lock) { entries.values.forEach { it.history?.clear() } }
    }

    /** Replaces the per-model overrides (key = sensor id key). */
    fun setOverrides(newOverrides: Map<String, SensorOverride>, nowMs: Long) {
        synchronized(lock) {
            if (newOverrides == overrides) return
            val affected = overrides.keys + newOverrides.keys
            val before = HashMap<SensorId, SensorValue?>()
            for (key in affected) {
                val id = SensorId(key)
                val entry = entries[id] ?: continue
                if (flows.containsKey(id)) before[id] = valueOf(entry)
            }
            overrides = newOverrides
            for ((id, old) in before) emit(id, entries.getValue(id), old, nowMs)
            valuesDirty = true
            listDirty = true
        }
    }

    /** Current value of [id] with overrides applied; null if the sensor is unknown. */
    fun value(id: SensorId): SensorValue? = synchronized(lock) { entries[id]?.let(::valueOf) }

    /** Metadata of [id] with overrides applied; null if the sensor is unknown. */
    fun info(id: SensorId): SensorInfo? = synchronized(lock) { entries[id]?.let { infoOf(it.info) } }

    /** Applies the overrides to metadata that is not stored here (the live sensors). */
    fun withOverrides(info: SensorInfo): SensorInfo = synchronized(lock) { infoOf(info) }

    /** Unit and precision overrides applied to a value that is not stored here (the live sensors). */
    fun withOverrides(id: SensorId, value: SensorValue): SensorValue = synchronized(lock) {
        val override = overrides[id.key] ?: return@synchronized value
        value.copy(unit = override.unit ?: value.unit, precision = override.precision ?: value.precision)
    }

    /** Values of all sensors that delivered at least one sample (or were published). */
    fun snapshot(): Map<SensorId, SensorValue> = synchronized(lock) {
        val result = LinkedHashMap<SensorId, SensorValue>(entries.size)
        for ((id, entry) in entries) if (entry.seen) result[id] = valueOf(entry)
        result
    }

    /** Metadata of all registered sensors, overrides applied, unsorted. */
    fun infos(): List<SensorInfo> = synchronized(lock) { entries.values.map { infoOf(it.info) } }

    /** Samples of [id] not older than [windowSec] seconds, oldest first. */
    fun history(id: SensorId, windowSec: Int, nowMs: Long): List<HistoryPoint> = synchronized(lock) {
        val history = entries[id]?.history ?: return@synchronized emptyList()
        val window = windowSec.coerceIn(1, historyCapacity * historyMinIntervalMs.toInt() / 1000).toLong() * 1000L
        history.since(nowMs - window)
    }

    /** The per-sensor flow of [id]; holds null while the sensor is unknown. */
    fun flow(id: SensorId): StateFlow<SensorValue?> = synchronized(lock) {
        flows.getOrPut(id) { MutableStateFlow(entries[id]?.takeIf { it.seen }?.let(::valueOf)) }
    }

    /** True once since the last call if any value changed (drives the throttled map). */
    fun consumeValuesDirty(): Boolean = synchronized(lock) { valuesDirty.also { valuesDirty = false } }

    /** True once since the last call if the set of sensors or their metadata changed. */
    fun consumeListDirty(): Boolean = synchronized(lock) { listDirty.also { listDirty = false } }

    // -- internals (call with the lock held) ------------------------------------------------------

    private fun entry(id: SensorId, info: SensorInfo?, policy: LostPolicy?): Entry =
        entries.getOrPut(id) {
            listDirty = true
            Entry(info ?: SensorCatalog.defaultInfo(id), policy ?: SensorCatalog.policyOf(id))
        }

    private fun valueOf(entry: Entry): SensorValue {
        val override = overrides[entry.info.id.key]
        return SensorValue(
            value = entry.value,
            text = entry.text,
            unit = override?.unit ?: entry.info.unit,
            precision = override?.precision ?: entry.info.precision,
            min = entry.min,
            max = entry.max,
            timestampMs = entry.timestampMs,
            lost = entry.lost,
        )
    }

    private fun infoOf(info: SensorInfo): SensorInfo {
        val override = overrides[info.id.key] ?: return info
        return info.copy(
            name = override.name?.takeIf { it.isNotBlank() } ?: info.name,
            defaultName = info.defaultName,
            unit = override.unit ?: info.unit,
            precision = override.precision ?: info.precision,
            hidden = override.hidden,
        )
    }

    /**
     * Pushes the new state of a sensor into its flow, if someone asked for one: always when
     * something other than the timestamp changed, otherwise at most every [flowRefreshMs].
     */
    private fun emit(id: SensorId, entry: Entry, before: SensorValue?, nowMs: Long) {
        val flow = flows[id] ?: return
        if (!entry.seen) return
        val now = valueOf(entry)
        val significant = flow.value == null || before == null || !sameExceptTime(before, now)
        if (significant || nowMs - entry.lastFlowEmitMs >= flowRefreshMs) {
            entry.lastFlowEmitMs = nowMs
            flow.value = now
        }
    }

    private fun sameExceptTime(a: SensorValue, b: SensorValue): Boolean =
        sameNumber(a.value, b.value) && a.text == b.text && a.unit == b.unit && a.precision == b.precision &&
            sameNumber(a.min, b.min) && sameNumber(a.max, b.max) && a.lost == b.lost

    private fun sameNumber(a: Double, b: Double): Boolean = a == b || (a.isNaN() && b.isNaN())

    companion object {
        /** Default time without an update after which a telemetry sensor counts as lost. */
        const val DEFAULT_TIMEOUT_MS: Long = 6000L

        /** Metadata for a published text sensor without explicit info. */
        fun textInfo(id: SensorId, name: String): SensorInfo = SensorInfo(id = id, name = name, unit = SensorUnit.TEXT)
    }
}
