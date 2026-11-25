package org.openax12.sdk

import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.RadioStatus
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerNotify
import org.openax12.core.model.TimerReport
import org.openax12.core.model.TimerState
import org.openax12.core.model.Trims

/**
 * Builds [LiveSnapshot]s from the buffers of [NativeApi.readLive]. Confined to the SDK thread.
 *
 * Per frame it allocates only the five primitive arrays the snapshot takes ownership of; trims
 * and status objects are reused while their values do not change, and no strings are involved.
 */
internal class LiveFrameDecoder {

    private var sequence = 0L
    private var lastTrims: Trims = Trims.ZERO
    private var lastStatus: RadioStatus = RadioStatus.EMPTY

    /** The trims of the frame in [ints]; reuses the previous object when unchanged. */
    fun trims(ints: IntArray): Trims {
        val lv = ints[NativeLayout.LIVE_INT_TRIM_LV]
        val lh = ints[NativeLayout.LIVE_INT_TRIM_LH]
        val rv = ints[NativeLayout.LIVE_INT_TRIM_RV]
        val rh = ints[NativeLayout.LIVE_INT_TRIM_RH]
        val last = lastTrims
        if (last.leftVertical == lv && last.leftHorizontal == lh && last.rightVertical == rv && last.rightHorizontal == rh) {
            return last
        }
        return Trims(leftVertical = lv, leftHorizontal = lh, rightVertical = rv, rightHorizontal = rh).also { lastTrims = it }
    }

    /** The radio status of the frame in [ints] / [floats]; reuses the previous object when unchanged. */
    fun status(ints: IntArray, floats: FloatArray): RadioStatus {
        val status = RadioStatus(
            linkQuality = ints[NativeLayout.LIVE_INT_RF_LQ],
            rssiDbm = ints[NativeLayout.LIVE_INT_RF_DBM],
            lostCounter = ints[NativeLayout.LIVE_INT_RF_LOST_COUNT],
            txVoltage = floats[NativeLayout.LIVE_FLOAT_TX_VOLTAGE] * SdkAssumptions.VOLTAGE_SCALE,
            rxVoltage = floats[NativeLayout.LIVE_FLOAT_RX_VOLTAGE] * SdkAssumptions.VOLTAGE_SCALE,
            topKeyState = ints[NativeLayout.LIVE_INT_TOP_KEY_STATE],
            calibrationState = ints[NativeLayout.LIVE_INT_ADC_SET_STATE],
        )
        if (status == lastStatus) return lastStatus
        lastStatus = status
        return status
    }

    /**
     * Decodes one frame. Sources and channels are placed at the index they report when those
     * indices are a permutation ([SdkIndex.resolve]), otherwise at their position.
     *
     * @return the snapshot, or null if the buffers are too small to be a frame
     */
    fun decode(
        ints: IntArray,
        floats: FloatArray,
        timestampMs: Long,
        timers: List<TimerState>,
        counters: List<TimerState>,
    ): LiveSnapshot? {
        if (ints.size < NativeLayout.LIVE_INT_SIZE || floats.size < NativeLayout.LIVE_FLOAT_SIZE) return null
        val sourceCount = ints[NativeLayout.LIVE_INT_SOURCE_COUNT].coerceIn(0, NativeLayout.LIVE_MAX_SOURCES)
        val channelCount = ints[NativeLayout.LIVE_INT_CHANNEL_COUNT].coerceIn(0, NativeLayout.LIVE_MAX_CHANNELS)

        val sourceSlots = SdkIndex.resolve(
            IntArray(sourceCount) { ints[NativeLayout.LIVE_INT_SOURCES_OFFSET + it * NativeLayout.LIVE_SOURCE_STRIDE] },
        )
        val sourceRaw = IntArray(sourceCount)
        val sourceLogic = IntArray(sourceCount)
        val sourceOutput = IntArray(sourceCount)
        for (position in 0 until sourceCount) {
            val base = NativeLayout.LIVE_INT_SOURCES_OFFSET + position * NativeLayout.LIVE_SOURCE_STRIDE
            val slot = sourceSlots[position]
            val rawVal = ints[base + 1]
            sourceRaw[slot] = rawVal
            sourceLogic[slot] = SdkAssumptions.sourcePercent(rawVal)
            sourceOutput[slot] = SdkAssumptions.sourceOutputPercent(rawVal, ints[base + 3])
        }

        val channelSlots = SdkIndex.resolve(
            IntArray(channelCount) { ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET + it * NativeLayout.LIVE_CHANNEL_STRIDE] },
        )
        val channelOutput = IntArray(channelCount)
        val channelLogic = FloatArray(channelCount)
        for (position in 0 until channelCount) {
            val base = NativeLayout.LIVE_INT_CHANNELS_OFFSET + position * NativeLayout.LIVE_CHANNEL_STRIDE
            val slot = channelSlots[position]
            channelOutput[slot] = SdkAssumptions.channelPercent(ints[base + 1])
            channelLogic[slot] = floats[NativeLayout.LIVE_FLOAT_CHANNELS_OFFSET + position]
        }

        sequence += 1
        return LiveSnapshot(
            sequence = sequence,
            timestampMs = timestampMs,
            sourceRaw = sourceRaw,
            sourceLogic = sourceLogic,
            sourceOutput = sourceOutput,
            channelOutput = channelOutput,
            channelLogic = channelLogic,
            trims = trims(ints),
            status = status(ints, floats),
            timers = timers,
            counters = counters,
        )
    }
}

/**
 * Keeps the latest state of the three timers and three counters, assembled from the report and
 * notification messages (there is no getter for the running state). Confined to the SDK thread.
 *
 * The lists are replaced only when something changed, so that consecutive live frames share them.
 * How `timeIdx` of the messages is interpreted lives in [SdkAssumptions] (assumption 21).
 */
internal class TimerTracker(private val staleMs: Long = SdkAssumptions.TIMER_RUNNING_STALE_MS) {

    var timers: List<TimerState> = LiveSnapshot.DEFAULT_TIMERS
        private set
    var counters: List<TimerState> = LiveSnapshot.DEFAULT_COUNTERS
        private set

    private val timerChangedAt = LongArray(TIMER_COUNT)

    fun reset() {
        timers = LiveSnapshot.DEFAULT_TIMERS
        counters = LiveSnapshot.DEFAULT_COUNTERS
        timerChangedAt.fill(0L)
    }

    /** A timer ([isCounter] false) or counter report. A timer whose count moved is running. */
    fun onReport(report: TimerReport, isCounter: Boolean, nowMs: Long) {
        val index = SdkAssumptions.timerOfReport(report.index, isCounter) ?: return
        val old = (if (isCounter) counters else timers)[index]
        val moved = report.count != old.count
        val state = old.copy(
            count = report.count,
            showsElapsed = report.showsElapsed,
            startTime = report.startTime,
            running = if (isCounter) false else (old.running || moved),
        )
        if (!isCounter && moved) timerChangedAt[index] = nowMs
        store(index, isCounter, state)
    }

    /** A timer notification: carries the running flag explicitly. */
    fun onNotify(notify: TimerNotify, nowMs: Long) {
        val (index, isCounter) = SdkAssumptions.timerOfNotification(notify.index) ?: return
        val old = (if (isCounter) counters else timers)[index]
        val running = !isCounter && notify.running && notify.code != TimerAlertMode.NOTIFY_RESET
        if (!isCounter && running) timerChangedAt[index] = nowMs
        store(index, isCounter, old.copy(count = notify.count, running = running))
    }

    /** Marks timers as stopped whose count did not move for [staleMs]. */
    fun refresh(nowMs: Long) {
        for (index in 0 until TIMER_COUNT) {
            val state = timers[index]
            if (state.running && nowMs - timerChangedAt[index] > staleMs) store(index, false, state.copy(running = false))
        }
    }

    private fun store(index: Int, isCounter: Boolean, state: TimerState) {
        val list = if (isCounter) counters else timers
        if (list[index] == state) return
        val updated = List(list.size) { if (it == index) state else list[it] }
        if (isCounter) counters = updated else timers = updated
    }
}

/**
 * Tracks the ELRS parameter menu: which item indices the SDK delivered since the last request
 * (only those may be used with `rcElrsSendMenuCmd`) and whether a request is still in progress
 * (the SDK ignores further requests then and sends no completion message, assumption 34).
 * Confined to the SDK thread.
 */
internal class ElrsMenuTracker(
    private val firstItemTimeoutMs: Long = SdkAssumptions.ELRS_MENU_FIRST_ITEM_TIMEOUT_MS,
    private val idleMs: Long = SdkAssumptions.ELRS_MENU_IDLE_MS,
) {
    private val known = HashSet<Int>()
    private var requestOpen = false
    private var requestedAt = 0L
    private var lastItemAt = 0L
    private var itemSeen = false

    /** True while the request started by [onRequest] is still delivering items. */
    fun isRequestOpen(nowMs: Long): Boolean {
        closeIfExpired(nowMs)
        return requestOpen
    }

    /** A new menu request was handed to the SDK: forget the old items. */
    fun onRequest(nowMs: Long) {
        known.clear()
        requestOpen = true
        requestedAt = nowMs
        lastItemAt = nowMs
        itemSeen = false
    }

    /** A menu item arrived (also after a command, when no request is open). */
    fun onItem(index: Int, nowMs: Long) {
        closeIfExpired(nowMs)
        known.add(index)
        if (requestOpen) {
            itemSeen = true
            lastItemAt = nowMs
        }
    }

    /** True if [index] was delivered since the last request. */
    fun isKnown(index: Int): Boolean = index in known

    fun reset() {
        known.clear()
        requestOpen = false
        itemSeen = false
    }

    private fun closeIfExpired(nowMs: Long) {
        if (!requestOpen) return
        val expired = if (itemSeen) nowMs - lastItemAt >= idleMs else nowMs - requestedAt >= firstItemTimeoutMs
        if (expired) requestOpen = false
    }
}
