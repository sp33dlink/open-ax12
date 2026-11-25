package org.openax12.core.audio

import org.openax12.core.model.AudioCategory
import org.openax12.core.model.SpeechPriority

/** What a queue entry plays. */
internal enum class ItemKind {
    /** Text for the speech engine. */
    SPEECH,

    /** A user sound file (content = file name relative to the sounds folder). */
    SOUND,
}

/**
 * One entry of the speech / sound queue.
 *
 * @property id unique per queue, increasing
 * @property content text to speak or sound file name; also the de-duplication key
 * @property enqueuedAtMs monotonic time of the request, for expiry
 */
internal data class QueueItem(
    val id: Long,
    val kind: ItemKind,
    val content: String,
    val priority: SpeechPriority,
    val category: AudioCategory,
    val enqueuedAtMs: Long,
)

/**
 * The priority queue shared by speech and sound files. Pure bookkeeping: it never plays anything
 * and is not thread-safe (the audio engine confines it to its control thread).
 *
 * Rules:
 * - Higher priorities are played first, first-in first-out within one priority.
 * - [SpeechPriority.LOW] is only accepted while nothing is playing or waiting.
 * - [SpeechPriority.ALARM] removes every waiting entry of a lower priority and asks the caller to
 *   interrupt what is playing unless that is an alarm itself.
 * - An entry identical (kind + content) to one already waiting is not queued twice; if the new
 *   request has a higher priority the waiting one is upgraded instead.
 * - At most [capacity] entries wait. A new entry may push out the newest entry of the lowest
 *   priority if that priority is lower than its own, otherwise it is dropped.
 * - Entries that waited longer than [maxAgeMs] for their priority are discarded: a late
 *   announcement is worse than none, and a stuck trigger cannot build up a backlog.
 */
internal class SpeechQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAgeMs: (SpeechPriority) -> Long = ::defaultMaxAgeMs,
) {

    /** Why a request was not queued. */
    enum class DropReason { EMPTY, BUSY, DUPLICATE, FULL, UNAVAILABLE }

    /** Result of [offer]. */
    sealed interface Offer {
        /**
         * The request waits in the queue.
         *
         * @property preempt the caller must stop [current] now (an alarm arrived)
         */
        data class Accepted(val item: QueueItem, val preempt: Boolean) : Offer

        data class Dropped(val reason: DropReason) : Offer
    }

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayList<QueueItem>()
    private var nextId = 1L

    /** The entry being played, set by [poll] and cleared by [finish]. */
    var current: QueueItem? = null
        private set

    /** Waiting entries in play order. */
    val pending: List<QueueItem> get() = items.toList()

    /** Number of waiting entries. */
    val size: Int get() = items.size

    /** True while something plays or waits. */
    val isBusy: Boolean get() = current != null || items.isNotEmpty()

    /**
     * Requests playback of [content].
     *
     * @param flush drop waiting entries of the same or a lower priority first
     * @param nowMs monotonic clock
     */
    fun offer(
        kind: ItemKind,
        content: String,
        priority: SpeechPriority,
        category: AudioCategory,
        flush: Boolean,
        nowMs: Long,
    ): Offer {
        if (content.isBlank()) return Offer.Dropped(DropReason.EMPTY)
        expire(nowMs)
        if (priority == SpeechPriority.LOW && isBusy) return Offer.Dropped(DropReason.BUSY)

        if (flush) items.removeAll { it.priority <= priority }
        if (priority == SpeechPriority.ALARM) items.removeAll { it.priority < SpeechPriority.ALARM }

        val duplicate = items.indexOfFirst { it.kind == kind && it.content == content }
        if (duplicate >= 0) {
            if (items[duplicate].priority >= priority) return Offer.Dropped(DropReason.DUPLICATE)
            items.removeAt(duplicate)
        }

        if (items.size >= capacity) {
            // Victim: the newest entry of the lowest priority, i.e. the last one in play order.
            val victim = items.last()
            if (victim.priority >= priority) return Offer.Dropped(DropReason.FULL)
            items.removeAt(items.lastIndex)
        }

        val item = QueueItem(nextId++, kind, content, priority, category, nowMs)
        var index = items.size
        while (index > 0 && items[index - 1].priority < priority) index--
        items.add(index, item)

        val playing = current
        val preempt = priority == SpeechPriority.ALARM && playing != null && playing.priority < SpeechPriority.ALARM
        return Offer.Accepted(item, preempt)
    }

    /**
     * Takes the next entry to play and makes it [current]. Returns null while something is still
     * playing or nothing playable waits.
     *
     * @param playable entries for which this returns false stay in the queue (their output is not
     *   ready yet) and later entries may overtake them
     */
    fun poll(nowMs: Long, playable: (QueueItem) -> Boolean = { true }): QueueItem? {
        if (current != null) return null
        expire(nowMs)
        val index = items.indexOfFirst(playable)
        if (index < 0) return null
        return items.removeAt(index).also { current = it }
    }

    /** Marks the entry [id] as done. Returns false if it is not the current one (stale callback). */
    fun finish(id: Long): Boolean {
        if (current?.id != id) return false
        current = null
        return true
    }

    /** Drops waiting entries of [category] (all when null); returns how many. */
    fun flush(category: AudioCategory? = null): Int {
        val before = items.size
        if (category == null) items.clear() else items.removeAll { it.category == category }
        return before - items.size
    }

    /** Drops waiting entries matching [predicate]; returns how many. */
    fun removeIf(predicate: (QueueItem) -> Boolean): Int {
        val before = items.size
        items.removeAll(predicate)
        return before - items.size
    }

    /** Forgets everything, including [current]. */
    fun clear() {
        items.clear()
        current = null
    }

    private fun expire(nowMs: Long) {
        if (items.isEmpty()) return
        items.removeAll { nowMs - it.enqueuedAtMs > maxAgeMs(it.priority) }
    }

    companion object {
        /** Waiting entries; with typical 2-3 s announcements this is well under half a minute. */
        const val DEFAULT_CAPACITY = 8

        /** How long an entry may wait before it is discarded. */
        fun defaultMaxAgeMs(priority: SpeechPriority): Long = when (priority) {
            SpeechPriority.LOW -> 3_000L
            SpeechPriority.NORMAL -> 15_000L
            SpeechPriority.HIGH -> 20_000L
            SpeechPriority.ALARM -> 30_000L
        }
    }
}

/**
 * Drives a [SpeechQueue]: starts the next entry when the previous one ends, interrupts for alarms
 * and reports the busy state. Pure logic over [Output]; not thread-safe (control thread only).
 */
internal class PlaybackController(
    private val output: Output,
    private val clock: () -> Long,
    private val queue: SpeechQueue = SpeechQueue(),
) {

    /** Whether an output can play right now. */
    enum class Availability {
        READY,

        /** Not yet (speech engine still binding): entries wait. */
        WAIT,

        /** Never (no speech engine): entries are dropped. */
        NEVER,
    }

    /** The players behind the queue. */
    interface Output {
        fun availability(kind: ItemKind): Availability

        /**
         * Starts playing [item]. When it ends for any reason other than [cancel] the output must
         * call [PlaybackController.onFinished] with its id.
         *
         * @return false if it could not be started; the controller moves on
         */
        fun begin(item: QueueItem): Boolean

        /** Stops [item] immediately. A late [PlaybackController.onFinished] for it is ignored. */
        fun cancel(item: QueueItem)

        /**
         * @param playing something is audible now (duck music, hold audio focus)
         * @param busy something is playing or waiting
         */
        fun onStateChanged(playing: Boolean, busy: Boolean)
    }

    private var lastPlaying = false
    private var lastBusy = false

    /** The entry being played. */
    val current: QueueItem? get() = queue.current

    /** Waiting entries in play order. */
    val pending: List<QueueItem> get() = queue.pending

    /** Queues a request and starts it if nothing else plays. */
    fun submit(
        kind: ItemKind,
        content: String,
        priority: SpeechPriority,
        category: AudioCategory,
        flush: Boolean = false,
    ): SpeechQueue.Offer {
        if (output.availability(kind) == Availability.NEVER) {
            return SpeechQueue.Offer.Dropped(SpeechQueue.DropReason.UNAVAILABLE)
        }
        val offer = queue.offer(kind, content, priority, category, flush, clock())
        if (offer is SpeechQueue.Offer.Accepted && offer.preempt) {
            queue.current?.let { playing ->
                queue.finish(playing.id)
                output.cancel(playing)
            }
        }
        pump()
        return offer
    }

    /** The output finished the entry [id]. */
    fun onFinished(id: Long) {
        if (queue.finish(id)) pump()
    }

    /** The availability of an output changed (speech engine became ready or failed). */
    fun onAvailabilityChanged() {
        queue.removeIf { output.availability(it.kind) == Availability.NEVER }
        pump()
    }

    /** Drops waiting entries of [category] (all when null). What is playing finishes. */
    fun flush(category: AudioCategory? = null) {
        queue.flush(category)
        publish()
    }

    /** Stops what is playing and drops everything waiting. */
    fun stopAll() {
        val playing = queue.current
        queue.clear()
        playing?.let(output::cancel)
        publish()
    }

    /** Starts the next playable entry if nothing is playing. */
    fun pump() {
        while (queue.current == null) {
            val item = queue.poll(clock()) { output.availability(it.kind) != Availability.WAIT } ?: break
            if (output.availability(item.kind) == Availability.READY && output.begin(item)) break
            queue.finish(item.id)
        }
        publish()
    }

    private fun publish() {
        val playing = queue.current != null
        val busy = queue.isBusy
        if (playing != lastPlaying || busy != lastBusy) {
            lastPlaying = playing
            lastBusy = busy
            output.onStateChanged(playing, busy)
        }
    }
}
