package org.openax12.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.openax12.core.data.ModelEntry
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.HomePreferences
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

    private val AX12 = setOf(Sources.SA, Sources.SB, Sources.SC, Sources.SD, Sources.SE, Sources.SF, Sources.S1, Sources.S2, Sources.SP)

    @Test
    fun `without a source list the panel is the stock layout without the SP row`() {
        val panel = planInputs(org.openax12.core.model.WorkingConfig.EMPTY)
        assertEquals(listOf("SA", "SB", "SC", "SD", "SE", "SF"), panel.switches.map { it.label })
        assertEquals(listOf("S1", "S2"), panel.knobs.map { it.label })
        assertFalse(panel.showSpButtons)
    }

    @Test
    fun `an AX12 shows SA-SF, S1, S2 and the SP row on one page`() {
        val panel = planInputs(config(AX12))
        assertEquals(listOf("SA", "SB", "SC", "SD", "SE", "SF"), panel.switches.map { it.label })
        assertEquals(listOf("S1", "S2"), panel.knobs.map { it.label })
        assertTrue(panel.showSpButtons)
    }

    @Test
    fun `the SP row is hidden when the radio reports SP as absent`() {
        val panel = planInputs(config(AX12 - Sources.SP))
        assertFalse(panel.showSpButtons)
        assertEquals(6, panel.switches.size)
    }

    @Test
    fun `SP is never drawn as a switch pill`() {
        val panel = planInputs(config(setOf(Sources.SA, Sources.SP)))
        assertEquals(listOf("SA"), panel.switches.map { it.label })
        assertTrue(panel.showSpButtons)
    }

    @Test
    fun `disabled sources are skipped`() {
        val panel = planInputs(config(setOf(Sources.SA, Sources.SC, Sources.S2)))
        assertEquals(listOf("SA", "SC"), panel.switches.map { it.label })
        assertEquals(listOf("S2"), panel.knobs.map { it.label })
    }

    @Test
    fun `the panel is capped at six pills and two gauges, sliders first`() {
        val enabled = (Sources.SA..Sources.SH).toSet() + setOf(Sources.S1, Sources.S2, Sources.P1, Sources.P2, Sources.CNT1)
        val panel = planInputs(config(enabled))
        assertEquals(listOf("SA", "SB", "SC", "SD", "SE", "SF"), panel.switches.map { it.label })
        assertEquals(listOf("S1", "S2"), panel.knobs.map { it.label })
        // Without sliders the pots take their place.
        assertEquals(listOf("P1", "P2"), planInputs(config(setOf(Sources.P1, Sources.P2, Sources.CNT1))).knobs.map { it.label })
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
        assertEquals(listOf("SA", "Arm"), planInputs(cfg).switches.map { it.label })
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
            home = HomePreferences(centerCard = HomeCenterCard.WIDGETS, channelPage = 7),
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
        // The remembered channel page is clamped to what exists.
        assertEquals(CHANNEL_PAGES - 1, state.channelPage)
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
