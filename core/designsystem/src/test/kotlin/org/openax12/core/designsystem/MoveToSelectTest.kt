package org.openax12.core.designsystem

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.SourceId

@OptIn(ExperimentalCoroutinesApi::class)
class MoveToSelectTest {

    private fun frame(vararg logic: Pair<SourceId, Int>, sequence: Long = 1) =
        LiveSnapshot.of(sequence = sequence, logic = logic.toMap())

    private val options = listOf(
        PickerOption(value = "ail", label = "Ail", source = SourceId.AIL),
        PickerOption(value = "ele", label = "Ele", source = SourceId.ELE),
        PickerOption(value = "sa", label = "SA", source = SourceId.SA),
        PickerOption(value = "locked", label = "Rud", source = SourceId.RUD, enabled = false),
        PickerOption(value = "none", label = "No source"),
    )

    @Test
    fun `only enabled options with a source are targets`() {
        val targets = moveToSelectTargets(options)
        assertEquals(setOf(SourceId.AIL, SourceId.ELE, SourceId.SA), targets.keys)
        assertEquals("ele", targets.getValue(SourceId.ELE).value)
    }

    @Test
    fun `a picker without sources has no targets`() {
        assertTrue(moveToSelectTargets(listOf(PickerOption(1, "One"), PickerOption(2, "Two"))).isEmpty())
    }

    @Test
    fun `moving a stick yields the option of that source`() = runTest {
        val feed = MutableStateFlow(frame(SourceId.AIL to 0, SourceId.ELE to 0, SourceId.SA to -100))
        val targets = moveToSelectTargets(options)
        val result = async { feed.awaitMovedInput(targets.keys) }
        runCurrent()
        assertFalse(result.isCompleted)
        feed.value = frame(SourceId.AIL to 6, SourceId.ELE to 4, SourceId.SA to -100, sequence = 2)
        runCurrent()
        assertFalse("drift below 10 % must not select", result.isCompleted)
        feed.value = frame(SourceId.AIL to 6, SourceId.ELE to 55, SourceId.SA to -100, sequence = 3)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals("ele", targets.getValue(result.await()!!).value)
    }

    @Test
    fun `a disabled option cannot be selected by moving its input`() = runTest {
        val feed = MutableStateFlow(frame(SourceId.AIL to 0, SourceId.RUD to 0))
        val targets = moveToSelectTargets(options)
        val result = async { feed.awaitMovedInput(targets.keys) }
        runCurrent()
        feed.value = frame(SourceId.AIL to 0, SourceId.RUD to 90, sequence = 2)
        runCurrent()
        assertFalse(result.isCompleted)
        feed.value = frame(SourceId.AIL to 0, SourceId.RUD to 90, SourceId.SA to 100, sequence = 3)
        runCurrent()
        assertEquals(SourceId.SA, result.await())
        assertNull(targets[SourceId.RUD])
    }

    @Test
    fun `the baseline is taken from the frame current when the picker opens`() = runTest {
        // The stick is already far out when the picker opens: that is not a movement.
        val feed = MutableStateFlow(frame(SourceId.AIL to 80))
        val result = async { feed.awaitMovedInput(listOf(SourceId.AIL)) }
        runCurrent()
        feed.value = frame(SourceId.AIL to 82, sequence = 2)
        runCurrent()
        assertFalse(result.isCompleted)
        feed.value = frame(SourceId.AIL to 20, sequence = 3)
        runCurrent()
        assertEquals(SourceId.AIL, result.await())
    }

    @Test
    fun `nothing to watch returns null at once`() = runTest {
        val feed = MutableStateFlow(frame(SourceId.AIL to 0))
        assertNull(feed.awaitMovedInput(emptyList()))
        assertNull(feed.awaitMovedInput(listOf(SourceId.counter(1))))
    }

    @Test
    fun `sources reported absent are not watched`() = runTest {
        val feed = MutableStateFlow(frame(SourceId.AIL to 0))
        assertNull(feed.awaitMovedInput(listOf(SourceId.AIL)) { false })
    }
}
