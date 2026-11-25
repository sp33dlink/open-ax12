package org.openax12.feature.models

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.data.ModelSnapshot
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceWriteGuardTest {

    private class Rig(dirty: Boolean = false, var linkUp: Boolean = false, scope: TestScope) {
        val session = SessionFake().also { it.dirty.value = dirty }
        val messages = mutableListOf<UiMessage>()
        val ran = mutableListOf<Boolean>()
        val guard = DeviceWriteGuard(scope, session.session, { linkUp }) { messages += it }
        fun request(askUnsaved: Boolean = true) = guard.request(askUnsaved) { ran += it }
    }

    private fun snapshot() = ModelSnapshot("s", "m", 0)

    @Test
    fun `clean model and no link runs the action at once`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(scope = this)
        rig.request()
        assertEquals(listOf(false), rig.ran)
        assertEquals(GuardStep.NONE, rig.guard.state.value.step)
    }

    @Test
    fun `link up asks before running`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(linkUp = true, scope = this)
        rig.request()
        assertEquals(GuardStep.LINK_UP, rig.guard.state.value.step)
        assertTrue(rig.ran.isEmpty())
        rig.guard.confirmLink()
        assertEquals(listOf(false), rig.ran)
        assertEquals(GuardStep.NONE, rig.guard.state.value.step)
    }

    @Test
    fun `cancel at the link question runs nothing`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(linkUp = true, scope = this)
        rig.request()
        rig.guard.cancel()
        assertTrue(rig.ran.isEmpty())
        assertEquals(GuardStep.NONE, rig.guard.state.value.step)
    }

    @Test
    fun `discard continues with the discard flag and still asks about the link`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(dirty = true, linkUp = true, scope = this)
        rig.request()
        assertEquals(GuardStep.UNSAVED, rig.guard.state.value.step)
        rig.guard.discardThenContinue()
        assertEquals(GuardStep.LINK_UP, rig.guard.state.value.step)
        rig.guard.confirmLink()
        assertEquals(listOf(true), rig.ran)
    }

    @Test
    fun `save then continue saves first`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(dirty = true, scope = this)
        rig.session.handlers["save"] = { rig.session.calls += "save"; SessionResult.Success(snapshot()) }
        rig.request()
        rig.guard.saveThenContinue()
        assertEquals(listOf("save"), rig.session.calls)
        assertEquals(listOf(false), rig.ran)
    }

    @Test
    fun `a failed save cancels the request and reports`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(dirty = true, scope = this)
        rig.session.handlers["save"] = { SessionResult.Failure(SessionError.DEVICE_TIMEOUT) }
        rig.request()
        rig.guard.saveThenContinue()
        assertTrue(rig.ran.isEmpty())
        assertEquals(GuardStep.NONE, rig.guard.state.value.step)
        assertEquals(R.string.models_err_device_timeout, rig.messages.single().res)
    }

    @Test
    fun `operations that drop changes by design skip the unsaved question`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(dirty = true, scope = this)
        rig.request(askUnsaved = false)
        assertEquals(listOf(false), rig.ran)
    }

    @Test
    fun `a second request while one is pending is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(linkUp = true, scope = this)
        rig.request()
        rig.request()
        rig.guard.confirmLink()
        assertEquals(1, rig.ran.size)
    }
}
