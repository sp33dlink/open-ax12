package org.openax12.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.SpeechPriority

class SpeechQueueTest {

    private fun SpeechQueue.say(
        text: String,
        priority: SpeechPriority = SpeechPriority.NORMAL,
        category: AudioCategory = AudioCategory.VOICE,
        flush: Boolean = false,
        nowMs: Long = 0,
        kind: ItemKind = ItemKind.SPEECH,
    ) = offer(kind, text, priority, category, flush, nowMs)

    private fun SpeechQueue.texts() = pending.map { it.content }

    private fun dropped(offer: SpeechQueue.Offer) = (offer as SpeechQueue.Offer.Dropped).reason

    @Test
    fun `plays higher priorities first and keeps arrival order within a priority`() {
        val queue = SpeechQueue()
        queue.say("a")
        queue.say("b")
        queue.say("c", SpeechPriority.HIGH)
        queue.say("d", SpeechPriority.HIGH)
        queue.say("e")
        assertEquals(listOf("c", "d", "a", "b", "e"), queue.texts())
        assertEquals("c", queue.poll(0)?.content)
        assertNull("nothing else starts while one entry plays", queue.poll(0))
    }

    @Test
    fun `low priority is only accepted while idle`() {
        val queue = SpeechQueue()
        assertTrue(queue.say("first", SpeechPriority.LOW) is SpeechQueue.Offer.Accepted)
        assertEquals(SpeechQueue.DropReason.BUSY, dropped(queue.say("waiting behind", SpeechPriority.LOW)))
        val playing = queue.poll(0)!!
        assertEquals(SpeechQueue.DropReason.BUSY, dropped(queue.say("while playing", SpeechPriority.LOW)))
        queue.finish(playing.id)
        assertTrue(queue.say("idle again", SpeechPriority.LOW) is SpeechQueue.Offer.Accepted)
    }

    @Test
    fun `alarm flushes lower priorities and pre-empts what is playing`() {
        val queue = SpeechQueue()
        queue.say("playing")
        queue.poll(0)
        queue.say("normal")
        queue.say("high", SpeechPriority.HIGH)
        queue.say("earlier alarm", SpeechPriority.ALARM)

        val offer = queue.say("battery low", SpeechPriority.ALARM) as SpeechQueue.Offer.Accepted
        assertTrue(offer.preempt)
        assertEquals(listOf("earlier alarm", "battery low"), queue.texts())
    }

    @Test
    fun `alarm does not pre-empt another alarm`() {
        val queue = SpeechQueue()
        queue.say("first alarm", SpeechPriority.ALARM)
        queue.poll(0)
        val offer = queue.say("second alarm", SpeechPriority.ALARM) as SpeechQueue.Offer.Accepted
        assertFalse(offer.preempt)
        assertEquals("first alarm", queue.current?.content)
    }

    @Test
    fun `nothing to pre-empt while idle`() {
        val offer = SpeechQueue().say("alarm", SpeechPriority.ALARM) as SpeechQueue.Offer.Accepted
        assertFalse(offer.preempt)
    }

    @Test
    fun `identical waiting entries are not queued twice`() {
        val queue = SpeechQueue()
        queue.say("flaps down")
        assertEquals(SpeechQueue.DropReason.DUPLICATE, dropped(queue.say("flaps down")))
        assertTrue(queue.say("flaps down", SpeechPriority.LOW) is SpeechQueue.Offer.Dropped)
        assertEquals(1, queue.size)
    }

    @Test
    fun `a duplicate with a higher priority upgrades the waiting entry`() {
        val queue = SpeechQueue()
        queue.say("a")
        queue.say("flaps down")
        assertTrue(queue.say("flaps down", SpeechPriority.HIGH) is SpeechQueue.Offer.Accepted)
        assertEquals(listOf("flaps down", "a"), queue.texts())
        assertEquals(SpeechPriority.HIGH, queue.pending.first().priority)
    }

    @Test
    fun `speech and sound with the same content are different entries`() {
        val queue = SpeechQueue()
        queue.say("gear")
        assertTrue(queue.say("gear", kind = ItemKind.SOUND) is SpeechQueue.Offer.Accepted)
        assertEquals(2, queue.size)
    }

    @Test
    fun `the entry being played may be queued once more`() {
        val queue = SpeechQueue()
        queue.say("low battery")
        queue.poll(0)
        assertTrue(queue.say("low battery") is SpeechQueue.Offer.Accepted)
        assertEquals(SpeechQueue.DropReason.DUPLICATE, dropped(queue.say("low battery")))
    }

    @Test
    fun `the queue is bounded`() {
        val queue = SpeechQueue(capacity = 3)
        queue.say("a")
        queue.say("b")
        queue.say("c")
        assertEquals(SpeechQueue.DropReason.FULL, dropped(queue.say("d")))
        // A more important entry pushes out the newest unimportant one.
        assertTrue(queue.say("e", SpeechPriority.HIGH) is SpeechQueue.Offer.Accepted)
        assertEquals(listOf("e", "a", "b"), queue.texts())
    }

    @Test
    fun `a stuck trigger cannot build a backlog`() {
        val queue = SpeechQueue()
        repeat(10_000) { tick -> queue.say("value $tick", nowMs = tick * 40L) }
        assertTrue(queue.size <= SpeechQueue.DEFAULT_CAPACITY)
        // Same text over and over: one entry.
        val same = SpeechQueue()
        repeat(10_000) { tick -> same.say("sink alarm", SpeechPriority.ALARM, nowMs = tick * 40L) }
        assertEquals(1, same.size)
    }

    @Test
    fun `entries that waited too long are discarded`() {
        val queue = SpeechQueue()
        queue.say("old news", nowMs = 0)
        queue.say("old but important", SpeechPriority.HIGH, nowMs = 0)
        val late = SpeechQueue.defaultMaxAgeMs(SpeechPriority.NORMAL) + 1
        assertTrue(late < SpeechQueue.defaultMaxAgeMs(SpeechPriority.HIGH))
        assertEquals("old but important", queue.poll(late)?.content)
        assertEquals(0, queue.size)
    }

    @Test
    fun `flush flag drops the same and lower priorities`() {
        val queue = SpeechQueue()
        queue.say("low-ish")
        queue.say("important", SpeechPriority.HIGH)
        queue.say("replacement", flush = true)
        assertEquals(listOf("important", "replacement"), queue.texts())
    }

    @Test
    fun `flush by category keeps the others and the current entry`() {
        val queue = SpeechQueue()
        queue.say("playing", category = AudioCategory.VOICE)
        queue.poll(0)
        queue.say("voice", category = AudioCategory.VOICE)
        queue.say("alarm", category = AudioCategory.ALARM)
        assertEquals(1, queue.flush(AudioCategory.VOICE))
        assertEquals(listOf("alarm"), queue.texts())
        assertEquals("playing", queue.current?.content)
        assertEquals(1, queue.flush())
        assertTrue(queue.isBusy)
    }

    @Test
    fun `blank content is rejected`() {
        assertEquals(SpeechQueue.DropReason.EMPTY, dropped(SpeechQueue().say("   ")))
    }

    @Test
    fun `poll skips entries whose output is not ready`() {
        val queue = SpeechQueue()
        queue.say("speech")
        queue.say("sound.wav", kind = ItemKind.SOUND)
        val item = queue.poll(0) { it.kind == ItemKind.SOUND }
        assertEquals("sound.wav", item?.content)
        assertEquals(listOf("speech"), queue.texts())
    }

    @Test
    fun `finish ignores stale ids`() {
        val queue = SpeechQueue()
        queue.say("a")
        val item = queue.poll(0)!!
        assertFalse(queue.finish(item.id + 1))
        assertTrue(queue.finish(item.id))
        assertFalse(queue.finish(item.id))
        assertFalse(queue.isBusy)
    }
}
