package org.openax12.core.model

import kotlin.math.abs

/**
 * Detects which input the user moves while an input picker is open ("move to select").
 *
 * Usage: create one detector per opening of a picker, feed it every live frame via [update]; the
 * first frame with data becomes the baseline. [update] returns the source that moved away from its
 * baseline, or null while nothing did.
 *
 * Rules:
 * - Only [candidates] are watched: the sources the picker offers, minus those [isPresent] reports as
 *   absent, minus counters (they change by themselves, a person did not move them).
 * - Sticks, knobs and sliders count when their value differs from the baseline by more than
 *   [threshold] percentage points, so a stick that drifts a little does nothing.
 * - Switches count when their position changes (crossing ±50 %); SP, the button bar, when a
 *   different button becomes active.
 * - If several sources moved in the same frame the one with the largest movement wins (ties go
 *   to the lower source index).
 * - A frame that is [LiveSnapshot.isEmpty] (the radio has not reported yet) is skipped.
 *
 * Reading a frame allocates nothing. The class is not thread-safe; use it from one coroutine.
 *
 * @param candidates the sources to watch; invalid indices and duplicates are ignored
 * @param threshold movement in percentage points that has to be exceeded by an analog source
 * @param isPresent false for sources the radio reports as absent (not enabled)
 */
class MoveToSelectDetector(
    candidates: Collection<SourceId>,
    private val threshold: Int = THRESHOLD_PERCENT,
    isPresent: (SourceId) -> Boolean = { true },
) {
    private val watched: IntArray = candidates
        .asSequence()
        .filter { it.isValid && !it.isCounter && isPresent(it) }
        .map { it.index }
        .distinct()
        .sorted()
        .toList()
        .toIntArray()

    private val baseline = IntArray(watched.size)
    private var hasBaseline = false

    /** True if there is nothing to watch, so the picker should not advertise the feature. */
    val isEmpty: Boolean get() = watched.isEmpty()

    /** Forgets the baseline; the next frame with data becomes the new one. */
    fun reset() {
        hasBaseline = false
    }

    /** Feeds one live frame. Returns the source that was moved, or null. */
    fun update(live: LiveSnapshot): SourceId? {
        if (live.isEmpty || watched.isEmpty()) return null
        if (!hasBaseline) {
            for (i in watched.indices) baseline[i] = live.logicAt(watched[i])
            hasBaseline = true
            return null
        }
        var best = -1
        var bestMovement = 0
        for (i in watched.indices) {
            val index = watched[i]
            val now = live.logicAt(index)
            val was = baseline[i]
            val movement = abs(now - was)
            val moved = when {
                index == Sources.SP -> SpButtonBar.buttonFromLogic(now) != SpButtonBar.buttonFromLogic(was)
                index in Sources.SWITCHES -> SwitchPosition.fromLogic(now) != SwitchPosition.fromLogic(was)
                else -> movement > threshold
            }
            if (moved && movement > bestMovement) {
                best = index
                bestMovement = movement
            }
        }
        return if (best >= 0) SourceId(best) else null
    }

    companion object {
        /** A stick, knob or slider has to move more than this many percentage points. */
        const val THRESHOLD_PERCENT: Int = 10
    }
}
