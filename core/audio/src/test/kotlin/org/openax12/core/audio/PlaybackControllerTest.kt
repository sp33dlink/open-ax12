package org.openax12.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.audio.PlaybackController.Availability
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.SpeechPriority

class PlaybackControllerTest {

    private class FakeOutput : PlaybackController.Output {
        var speech = Availability.READY
        var sound = Availability.READY
        var failing = emptySet<String>()
        val begun = ArrayList<QueueItem>()
        val cancelled = ArrayList<QueueItem>()
        val states = ArrayList<Pair<Boolean, Boolean>>()

        override fun availability(kind: ItemKind) = if (kind == ItemKind.SPEECH) speech else sound

        override fun begin(item: QueueItem): Boolean {
            if (item.content in failing) return false
            begun += item
            return true
        }

        override fun cancel(item: QueueItem) {
            cancelled += item
        }

        override fun onStateChanged(playing: Boolean, busy: Boolean) {
            states += playing to busy
        }

        fun texts() = begun.map { it.content }
    }

    private var now = 0L
    private val output = FakeOutput()
    private val controller = PlaybackController(output, { now })

    private fun say(text: String, priority: SpeechPriority = SpeechPriority.NORMAL, kind: ItemKind = ItemKind.SPEECH) =
        controller.submit(kind, text, priority, AudioCategory.VOICE)

    @Test
    fun `plays one entry after the other`() {
        say("a")
        say("b")
        assertEquals(listOf("a"), output.texts())
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("a", "b"), output.texts())
        controller.onFinished(output.begun[1].id)
        assertNull(controller.current)
        assertEquals(listOf(true to true, false to false), output.states)
    }

    @Test
    fun `an alarm interrupts and flushes`() {
        say("long announcement")
        say("next")
        say("battery critical", SpeechPriority.ALARM)

        assertEquals(listOf("long announcement"), output.cancelled.map { it.content })
        assertEquals(listOf("long announcement", "battery critical"), output.texts())
        assertTrue(controller.pending.isEmpty())

        // The interrupted utterance reports its end late: must not end the alarm.
        controller.onFinished(output.begun[0].id)
        assertEquals("battery critical", controller.current?.content)
    }

    @Test
    fun `an alarm waits for a playing alarm`() {
        say("first alarm", SpeechPriority.ALARM)
        say("second alarm", SpeechPriority.ALARM)
        assertTrue(output.cancelled.isEmpty())
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("first alarm", "second alarm"), output.texts())
    }

    @Test
    fun `high priority jumps the queue without interrupting`() {
        say("a")
        say("b")
        say("urgent", SpeechPriority.HIGH)
        assertTrue(output.cancelled.isEmpty())
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("a", "urgent"), output.texts())
    }

    @Test
    fun `low priority is dropped while something plays`() {
        say("a")
        val offer = say("switch chatter", SpeechPriority.LOW)
        assertEquals(SpeechQueue.Offer.Dropped(SpeechQueue.DropReason.BUSY), offer)
    }

    @Test
    fun `speech waits for the engine while sounds overtake`() {
        output.speech = Availability.WAIT
        say("hello")
        assertTrue(output.begun.isEmpty())
        assertEquals(listOf(false to true), output.states)

        say("gear.wav", kind = ItemKind.SOUND)
        assertEquals(listOf("gear.wav"), output.texts())

        output.speech = Availability.READY
        controller.onAvailabilityChanged()
        assertEquals("still playing the sound", listOf("gear.wav"), output.texts())
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("gear.wav", "hello"), output.texts())
    }

    @Test
    fun `speech is dropped without an engine`() {
        output.speech = Availability.NEVER
        assertEquals(SpeechQueue.Offer.Dropped(SpeechQueue.DropReason.UNAVAILABLE), say("hello"))
        assertTrue(output.states.isEmpty())
        // Sounds still play.
        say("gear.wav", kind = ItemKind.SOUND)
        assertEquals(listOf("gear.wav"), output.texts())
    }

    @Test
    fun `waiting speech is dropped when the engine turns out to be missing`() {
        output.speech = Availability.WAIT
        say("hello")
        say("world")
        output.speech = Availability.NEVER
        controller.onAvailabilityChanged()
        assertTrue(controller.pending.isEmpty())
        assertEquals(false to false, output.states.last())
    }

    @Test
    fun `an entry that cannot start is skipped`() {
        output.failing = setOf("broken.wav")
        say("broken.wav", kind = ItemKind.SOUND)
        assertNull(controller.current)
        assertTrue(output.states.isEmpty())

        say("a")
        say("broken.wav", kind = ItemKind.SOUND)
        say("b")
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("a", "b"), output.texts())
    }

    @Test
    fun `expired entries are not played`() {
        say("a")
        say("stale")
        now = SpeechQueue.defaultMaxAgeMs(SpeechPriority.NORMAL) + 1
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("a"), output.texts())
        assertEquals(false to false, output.states.last())
    }

    @Test
    fun `flush keeps the current entry and stopAll cancels it`() {
        say("a")
        say("b")
        controller.flush()
        assertEquals("a", controller.current?.content)
        assertTrue(controller.pending.isEmpty())
        assertEquals(true to true, output.states.last())

        say("c")
        controller.stopAll()
        assertEquals(listOf("a"), output.cancelled.map { it.content })
        assertNull(controller.current)
        assertEquals(false to false, output.states.last())

        // The cancelled entry's late callback changes nothing.
        controller.onFinished(output.begun[0].id)
        assertEquals(listOf("a"), output.texts())
    }

    @Test
    fun `a repeating trigger never queues more than one copy`() {
        repeat(500) { say("sink rate") }
        assertEquals(1, controller.pending.size)
        assertEquals(listOf("sink rate"), output.texts())
    }
}
