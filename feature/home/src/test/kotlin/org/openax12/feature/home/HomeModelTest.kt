package org.openax12.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.openax12.core.data.ModelEntry
import org.openax12.core.model.FlightModeConfig
import org.openax12.core.model.FlightModesConfig
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.HomePreferences
import org.openax12.core.model.LogicalFunction
import org.openax12.core.model.LogicalSwitch
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.SystemConfig
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.TelemetryLayout
import org.openax12.core.model.TelemetryPage
import org.openax12.core.model.CompareOp
import org.junit.Test

class HomeModelTest {

    /** A source list in which only the given source indices are enabled. */
    private fun config(enabled: Set<Int>, names: Map<Int, String> = emptyMap(), stick: StickMode = StickMode.MODE_2) =
        org.openax12.core.model.WorkingConfig(
            sources = Sources.ALL.map { SourceInfo(index = it, name = names[it] ?: Sources.defaultName(it), enabled = it in enabled) },
            channels = List(16) { OutputChannelConfig(channel = it, mixCount = if (it == 2) 3 else 0) },
            system = SystemConfig(stickMode = stick),
        )

    private fun controls(page: HomePage?) = page as HomePage.Controls

    @Test
    fun `without a source list the first page is the stock layout`() {
        val pages = planPages(org.openax12.core.model.WorkingConfig.EMPTY, ModelExtras())
        assertEquals(1, pages.size)
        val page = controls(pages[0])
        assertEquals(listOf("SA", "SB", "SC", "SD", "SE", "SF"), page.switches.map { it.label })
        assertEquals(listOf("S1", "S2"), page.knobs.map { it.label })
    }

    @Test
    fun `switches and knobs are chunked per page`() {
        val enabled = (Sources.SA..Sources.SH).toSet() + setOf(Sources.S1, Sources.S2, Sources.P1, Sources.P2, Sources.CNT1)
        val pages = planPages(config(enabled), ModelExtras())
        // 8 switches -> 2 pages, 5 knobs -> 3 pages.
        assertEquals(3, pages.size)
        assertEquals(6, controls(pages[0]).switches.size)
        assertEquals(2, controls(pages[1]).switches.size)
        assertTrue(controls(pages[2]).switches.isEmpty())
        // Sliders first, then pots, then counters.
        assertEquals(listOf("S1", "S2"), controls(pages[0]).knobs.map { it.label })
        assertEquals(listOf("P1", "P2"), controls(pages[1]).knobs.map { it.label })
        assertEquals(listOf("CNT1"), controls(pages[2]).knobs.map { it.label })
    }

    @Test
    fun `disabled sources are skipped`() {
        val pages = planPages(config(setOf(Sources.SA, Sources.SC, Sources.S2)), ModelExtras())
        assertEquals(1, pages.size)
        assertEquals(listOf("SA", "SC"), controls(pages[0]).switches.map { it.label })
        assertEquals(listOf("S2"), controls(pages[0]).knobs.map { it.label })
    }

    @Test
    fun `knob ranges follow the source kind`() {
        assertEquals(KnobRange.ONE_SIDED, knobRange(SourceId.slider(1)))
        assertEquals(KnobRange.CENTERED, knobRange(SourceId.pot(1)))
        assertEquals(KnobRange.CENTERED, knobRange(SourceId.counter(2)))
        assertEquals(50f, knobPercent(0, KnobRange.ONE_SIDED), 0f)
        assertEquals(92f, knobPercent(84, KnobRange.ONE_SIDED), 0f)
        assertEquals(-40f, knobPercent(-40, KnobRange.CENTERED), 0f)
    }

    @Test
    fun `long user names fall back to the firmware name`() {
        val cfg = config(setOf(Sources.SA, Sources.SB), names = mapOf(Sources.SA to "Flaps", Sources.SB to "Arm"))
        val labels = controls(planPages(cfg, ModelExtras())[0]).switches.map { it.label }
        assertEquals(listOf("SA", "Arm"), labels)
    }

    @Test
    fun `flight mode page appears only when something is configured`() {
        val cfg = config(setOf(Sources.SA))
        assertEquals(1, planPages(cfg, ModelExtras()).size)

        val withLogic = ModelExtras(
            logicalSwitches = listOf(LogicalSwitch(index = 2, function = LogicalFunction.A_GREATER_X), LogicalSwitch(index = 5)),
        )
        val pages = planPages(cfg, withLogic)
        assertEquals(2, pages.size)
        val modes = pages.last() as HomePage.Modes
        assertFalse(modes.flightModesUsed)
        // The empty slot L6 is not listed.
        assertEquals(listOf("L3"), modes.logicSwitches.map { it.label })

        val withModes = ModelExtras(
            flightModes = FlightModesConfig(enabled = true, modeSwitch = SourceId.SB, modes = listOf(FlightModeConfig("Cruise"))),
        )
        assertTrue((planPages(cfg, withModes).last() as HomePage.Modes).flightModesUsed)
    }

    @Test
    fun `logical switch chips are capped and the rest is counted`() {
        val logic = List(15) { LogicalSwitch(index = it, function = LogicalFunction.A_GREATER_X) }
        val modes = planPages(config(setOf(Sources.SA)), ModelExtras(logicalSwitches = logic)).last() as HomePage.Modes
        assertEquals(LOGIC_CHIPS_PER_PAGE, modes.logicSwitches.size)
        assertEquals(5, modes.hiddenLogicSwitches)
    }

    @Test
    fun `page count is capped and the flight mode page is kept`() {
        // 16 switches and 8 pots + 8 sliders + 6 counters: far more than eight control pages.
        val enabled = (Sources.P1..Sources.CNT6).toSet()
        val extras = ModelExtras(logicalSwitches = listOf(LogicalSwitch(index = 0, function = LogicalFunction.A_GREATER_X)))
        val pages = planPages(config(enabled), extras)
        assertEquals(MAX_INPUT_PAGES, pages.size)
        assertTrue(pages.last() is HomePage.Modes)
    }

    @Test
    fun `timers list T1 and T2 always and T3 only when configured`() {
        assertEquals(listOf(0, 1), timerItems(org.openax12.core.model.WorkingConfig.EMPTY).map { it.index })
        val unused = config(emptySet()).copy(timers = List(3) { TimerConfig(index = it) })
        assertEquals(2, timerItems(unused).size)
        val withSwitch = unused.copy(
            timers = unused.timers.map {
                if (it.index == 2) it.copy(switch = SwitchCondition(SourceId.SA, CompareOp.EQUAL, 100)) else it
            },
        )
        assertEquals(listOf(0, 1, 2), timerItems(withSwitch).map { it.index })
        val withStart = unused.copy(timers = unused.timers.map { if (it.index == 2) it.copy(startTime = 600) else it })
        assertEquals(3, timerItems(withStart).size)
    }

    @Test
    fun `stick pads follow the stick mode`() {
        assertEquals(SourceId.THR, padVertical(StickMode.MODE_2, PadSide.LEFT))
        assertEquals(SourceId.RUD, padHorizontal(StickMode.MODE_2, PadSide.LEFT))
        assertEquals(SourceId.ELE, padVertical(StickMode.MODE_2, PadSide.RIGHT))
        assertEquals(SourceId.AIL, padHorizontal(StickMode.MODE_2, PadSide.RIGHT))
        assertEquals(SourceId.ELE, padVertical(StickMode.MODE_1, PadSide.LEFT))
        assertEquals(SourceId.THR, padVertical(StickMode.MODE_1, PadSide.RIGHT))
    }

    @Test
    fun `state takes model, extras and configuration together`() {
        val pinned = WidgetConfig(id = "w", pinned = true)
        val extras = ModelExtras(
            home = HomePreferences(centerCard = HomeCenterCard.WIDGETS, inputPage = 99, channelPage = 7),
            telemetry = TelemetryLayout(pages = listOf(TelemetryPage(id = "p", widgets = listOf(pinned, WidgetConfig(id = "x"))))),
        )
        val model = ModelEntry(id = "m", name = "Alula", picturePath = "/p.jpg", pictureVersion = 4)
        val state = buildHomeState(model, extras, config(setOf(Sources.SA), stick = StickMode.MODE_1))

        assertEquals("Alula", state.modelName)
        assertEquals("/p.jpg", state.picturePath)
        assertEquals(4L, state.pictureVersion)
        assertEquals(HomeCenterCard.WIDGETS, state.centerCard)
        assertEquals(listOf(pinned), state.pinnedWidgets)
        assertEquals(StickMode.MODE_1, state.stickMode)
        // Remembered pages are clamped to what exists.
        assertEquals(CHANNEL_PAGES - 1, state.channelPage)
        assertEquals(state.pages.size - 1, state.pageIndex)
        // Only CH03 has mixes.
        assertEquals(listOf(2), state.channels.filter { it.emphasized }.map { it.index })
    }

    @Test
    fun `visible channels follow the channel page`() {
        val base = buildHomeState(null, ModelExtras(), org.openax12.core.model.WorkingConfig.EMPTY)
        assertEquals((0..7).toList(), base.visibleChannels.map { it.index })
        assertEquals((8..15).toList(), base.copy(channelPage = 1).visibleChannels.map { it.index })
        assertEquals("", base.modelName)
    }
}
