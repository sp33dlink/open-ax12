package org.openax12.core.radio

import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.SourceId
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TelemetryAlarm
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.WorkingConfig

// Helpers built purely on the Radio interface, shared by every Radio user.
//
// The isValid… functions are cheap UI PRE-CHECKS against the compile-time upper bounds of
// :core:model (e.g. to grey out a picker entry). They are not the validation that protects the
// SDK: a Radio implementation checks every index against the lists the SDK actually returned
// (see "Invalid index" in the Radio KDoc), so an index that passes here can still be rejected.

/** UI pre-check: [channel] is within the upper bound of 0-based output channel indices. */
fun isValidChannel(channel: Int): Boolean = channel in 0 until CHANNEL_COUNT

/** UI pre-check: [index] is within the upper bound of curve indices ([NO_CURVE] is not). */
fun isValidCurveIndex(index: Int): Boolean = index in 0 until CURVE_COUNT

/** UI pre-check: [slot] is a dual-rate slot index. */
fun isValidDualRateSlot(slot: Int): Boolean = slot in 0 until DR_SLOTS

/** UI pre-check: [index] is an in-group timer / counter index. */
fun isValidTimerIndex(index: Int, isCounter: Boolean = false): Boolean =
    index in 0 until (if (isCounter) COUNTER_COUNT else TIMER_COUNT)

/** All mix lines of [channel] in evaluation order (uses the channel's mix count). */
suspend fun Radio.getMixes(channel: Int): List<MixLine> {
    if (!isValidChannel(channel)) return emptyList()
    val count = getOutputChannels().firstOrNull { it.channel == channel }?.mixCount ?: return emptyList()
    return (0 until count).mapNotNull { getMix(channel, it) }
}

/** The mix of [source] on [channel], or null. */
suspend fun Radio.findMix(channel: Int, source: SourceId): MixLine? =
    getMixes(channel).firstOrNull { it.source == source }

/** All [DR_SLOTS] dual-rate slots of [channel]. */
suspend fun Radio.getDualRates(channel: Int): List<DualRateSlot> {
    if (!isValidChannel(channel)) return emptyList()
    return (0 until DR_SLOTS).mapNotNull { getDualRate(channel, it) }
}

/**
 * The first disabled dual-rate slot of [channel] (lowest slot index), or null if all
 * [DR_SLOTS] are in use. This is how the app allocates slots — "disabled" is the app's definition
 * of "free" because the SDK cannot delete a slot ([Radio.findFreeDualRateSlot] is not used).
 */
suspend fun Radio.findDisabledDualRateSlot(channel: Int): DualRateSlot? =
    getDualRates(channel).firstOrNull { !it.isEnabled }

/**
 * The current model, resolved from the raw [Radio.getCurrentModelIndex] — the **only** place
 * that interprets that value (assumption 15). The value is matched against [ModelInfo.index] of
 * [Radio.getModels] first; if no model has that index it is taken as the position in the list.
 * Null if the SDK reported no current model or neither interpretation applies.
 *
 * [ModelInfo.index] is **not unique** on the real radio (models copied from one another keep the
 * `modelIdx` of the original; observed on the device). When several models carry the raw value
 * the choice is made without guessing "the first one": the one stored in [preferredFile] (the file
 * the caller believes is current), else the one whose list position equals the raw value, else the
 * first.
 *
 * @param preferredFile SDK file of the model the caller currently regards as current, if any
 */
suspend fun Radio.getCurrentModel(preferredFile: String? = null): ModelInfo? {
    val raw = getCurrentModelIndex() ?: return null
    val models = getModels()
    val byIndex = models.filter { it.index == raw.toLong() }
    val byPosition = models.getOrNull(raw)
    return when {
        byIndex.isEmpty() -> byPosition
        byIndex.size == 1 -> byIndex.first()
        else -> byIndex.firstOrNull { preferredFile != null && it.filePath == preferredFile }
            ?: byPosition?.takeIf { it in byIndex }
            ?: byIndex.first()
    }
}

/**
 * Adds an SDK-side telemetry notification for the telemetry item `alarm.hash`
 * (`rcSetNotifyCfgItem(0, alarm, -1)`). Rejected (`false`) when [TelemetryAlarm.hash] is 0: the
 * new notification would be bound to no item.
 */
suspend fun Radio.addTelemetryAlarm(alarm: TelemetryAlarm): Boolean =
    alarm.hash != 0L && setTelemetryAlarm(hash = 0L, alarm = alarm, index = -1)

/**
 * Modifies the SDK-side telemetry notification at [index] of [Radio.getTelemetryAlarms]
 * (`rcSetNotifyCfgItem(alarm.hash, alarm, index)`). Rejected (`false`) when [index] is not in the
 * current list or [TelemetryAlarm.hash] is 0.
 */
suspend fun Radio.updateTelemetryAlarm(index: Int, alarm: TelemetryAlarm): Boolean {
    if (alarm.hash == 0L || index !in getTelemetryAlarms().indices) return false
    return setTelemetryAlarm(hash = alarm.hash, alarm = alarm, index = index)
}

/** All timer configurations (or counters when [isCounter]). */
suspend fun Radio.getTimerConfigs(isCounter: Boolean = false): List<TimerConfig> =
    (0 until (if (isCounter) COUNTER_COUNT else TIMER_COUNT)).mapNotNull { getTimerConfig(it, isCounter) }

/** Drains the SDK's queue of triggered telemetry notifications (at most [max] items). */
suspend fun Radio.drainTelemetryNotifications(max: Int = 32): List<TelemetryAlarm> {
    val result = ArrayList<TelemetryAlarm>()
    while (result.size < max) {
        result += pollTelemetryNotification() ?: break
    }
    return result
}

/**
 * Reads the complete working configuration through the individual getters.
 *
 * Costs a few hundred cheap SDK calls (in-memory reads); call it when [Radio.configRevision]
 * changed, not per frame. If the radio is not usable — no channels, or the system configuration
 * cannot be read — the result is an empty config ([WorkingConfig.isEmpty]) with the current
 * revision. Defaults are never substituted for unreadable parts: a made-up `SystemConfig` would
 * be indistinguishable from a real one and could be written back to the radio.
 */
suspend fun Radio.readWorkingConfig(): WorkingConfig {
    val revision = configRevision.value
    val channels = getOutputChannels()
    if (channels.isEmpty()) return WorkingConfig(revision = revision)
    val system = getSystemConfig() ?: return WorkingConfig(revision = revision)
    val mixes = ArrayList<MixLine>()
    val dualRates = ArrayList<DualRateSlot>()
    for (channel in channels) {
        if (!isValidChannel(channel.channel)) continue
        for (mixIndex in 0 until channel.mixCount) {
            getMix(channel.channel, mixIndex)?.let(mixes::add)
        }
        for (slot in 0 until DR_SLOTS) {
            getDualRate(channel.channel, slot)?.let(dualRates::add)
        }
    }
    val curves: List<CurveConfig> = getCurves()
    return WorkingConfig(
        revision = revision,
        sources = getSources(),
        channels = channels,
        mixes = mixes,
        curves = curves,
        dualRates = dualRates,
        timers = getTimerConfigs(isCounter = false),
        counters = getTimerConfigs(isCounter = true),
        system = system,
    )
}
