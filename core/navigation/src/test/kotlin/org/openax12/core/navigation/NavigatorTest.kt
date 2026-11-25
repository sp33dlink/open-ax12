package org.openax12.core.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {

    private val stack = mutableListOf<NavKey>()
    private val navigator = Navigator(stack)

    @Test
    fun `starts at home`() {
        assertEquals(listOf<NavKey>(Home), stack)
        assertEquals(TopLevelDestination.HOME, navigator.currentTopLevel)
        assertFalse(navigator.canGoBack)
        assertFalse(navigator.back())
    }

    @Test
    fun `push is single-top`() {
        navigator.navigate(Diagnostics)
        navigator.navigate(Diagnostics)
        assertEquals(listOf(Home, Diagnostics), stack)
        assertTrue(navigator.back())
        assertEquals(listOf<NavKey>(Home), stack)
    }

    @Test
    fun `switching top level replaces everything above home`() {
        navigator.switchTopLevel(TopLevelDestination.MODELS)
        navigator.navigate(ModelDetail("a"))
        assertEquals(TopLevelDestination.MODELS, navigator.currentTopLevel)
        navigator.switchTopLevel(TopLevelDestination.TELEMETRY)
        assertEquals(listOf(Home, Telemetry), stack)
        navigator.navigate(SensorList)
        navigator.switchTopLevel(TopLevelDestination.TELEMETRY)
        assertEquals(listOf(Home, Telemetry), stack)
        navigator.switchTopLevel(TopLevelDestination.HOME)
        assertEquals(listOf<NavKey>(Home), stack)
    }

    @Test
    fun `navigating to a top-level key behaves like switching`() {
        navigator.navigate(Models)
        navigator.navigate(ModelSetup(SetupTabIds.TIMERS))
        assertEquals(listOf(Home, ModelSetup(SetupTabIds.TIMERS)), stack)
        assertEquals(TopLevelDestination.MODEL_SETUP, navigator.currentTopLevel)
    }

    @Test
    fun `replace and popTo`() {
        navigator.navigate(Models)
        navigator.navigate(ModelDetail("a"))
        navigator.navigate(PictureCrop("a", "content://x"))
        navigator.replace(ModelImport())
        assertEquals(listOf(Home, Models, ModelDetail("a"), ModelImport()), stack)
        assertTrue(navigator.popTo(Models))
        assertEquals(listOf(Home, Models), stack)
        assertFalse(navigator.popTo(Telemetry))
        assertTrue(navigator.popTo(Home, inclusive = true))
        assertEquals(listOf<NavKey>(Home), stack)
    }

    @Test
    fun `guard blocks leaving until proceed is called`() {
        val key = ModelSetup()
        navigator.navigate(key)
        val pending = mutableListOf<() -> Unit>()
        navigator.setGuard(key) { proceed -> pending.add(proceed); false }

        // Pushing a child does not leave the guarded entry.
        navigator.navigate(CurveEditor(3))
        assertEquals(listOf(Home, key, CurveEditor(3)), stack)
        assertTrue(navigator.back())
        assertTrue(pending.isEmpty())

        assertFalse(navigator.back())
        assertEquals(listOf(Home, key), stack)
        navigator.switchTopLevel(TopLevelDestination.MODELS)
        assertEquals(listOf(Home, key), stack)

        val proceed: () -> Unit = pending.last()
        proceed()
        assertEquals(listOf(Home, Models), stack)
        // The guard is gone after leaving.
        navigator.switchTopLevel(TopLevelDestination.HOME)
        assertEquals(listOf<NavKey>(Home), stack)
    }

    @Test
    fun `guard that allows lets navigation through`() {
        val key = ModelSetup()
        navigator.navigate(key)
        navigator.setGuard(key) { true }
        assertTrue(navigator.back())
        assertEquals(listOf<NavKey>(Home), stack)
        assertNull(TopLevelDestination.of(SensorList))
    }

    @Test
    fun `shell guard survives sub-editors and keeps protecting`() {
        navigator.navigate(ModelSetup())
        val pending = mutableListOf<() -> Unit>()
        navigator.guardEntries(protects = { it is ModelSetup }) { proceed -> pending.add(proceed); false }

        // A sub-editor on top: going back to model setup is free.
        navigator.navigate(LogicalSwitchEditor(2))
        assertTrue(navigator.back())
        assertTrue(pending.isEmpty())

        // Leaving model setup from inside a sub-editor asks.
        navigator.navigate(CurveEditor(1))
        navigator.switchTopLevel(TopLevelDestination.HOME)
        assertEquals(listOf(Home, ModelSetup(), CurveEditor(1)), stack)
        assertEquals(1, pending.size)

        pending.last().invoke()
        assertEquals(listOf<NavKey>(Home), stack)

        // The shell guard is not one-shot.
        navigator.navigate(ModelSetup())
        assertFalse(navigator.back())
        assertEquals(2, pending.size)
        navigator.clearGuard()
        assertTrue(navigator.back())
    }

    @Test
    fun `openSetupTab keeps the model setup entry and is not vetoed`() {
        navigator.navigate(ModelSetup(SetupTabIds.MIXES))
        navigator.guardEntries(protects = { it is ModelSetup }) { false }

        // Same key as the current entry, but the user may be on another tab by now.
        navigator.navigate(ModelSetup(SetupTabIds.MIXES))
        assertEquals(SetupTabIds.MIXES, navigator.setupTabRequest)
        assertEquals(SetupTabIds.MIXES, navigator.consumeSetupTabRequest())
        assertNull(navigator.setupTabRequest)

        // From a sub-editor: the editor is closed, the entry (and its key) stays.
        navigator.navigate(SpecialFunctionEditor(0))
        navigator.openSetupTab(SetupTabIds.CH_LOCK)
        assertEquals(listOf(Home, ModelSetup(SetupTabIds.MIXES)), stack)
        assertEquals(SetupTabIds.CH_LOCK, navigator.consumeSetupTabRequest())
    }

    @Test
    fun `openSetupTab from elsewhere switches to model setup`() {
        navigator.navigate(Telemetry)
        navigator.openSetupTab(SetupTabIds.TIMERS)
        assertEquals(listOf(Home, ModelSetup(SetupTabIds.TIMERS)), stack)
        assertNull(navigator.setupTabRequest)
        assertEquals(TopLevelDestination.MODEL_SETUP, navigator.currentTopLevel)
    }
}
