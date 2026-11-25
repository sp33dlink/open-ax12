package org.openax12.feature.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.audio.TtsStatus
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.ChecklistItem
import org.openax12.core.model.CurveType
import org.openax12.core.model.ElrsAck
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.ModelInfo
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfig
import org.openax12.core.radio.ConnectionError
import org.openax12.core.radio.ConnectionState
import org.openax12.feature.system.calibration.AxisExtent
import org.openax12.feature.system.calibration.StickExtents
import org.openax12.feature.system.common.formatBytes
import org.openax12.feature.system.common.formatDuration
import org.openax12.feature.system.common.formatFirmwareVersion
import org.openax12.feature.system.common.formatHardwareVersion
import org.openax12.feature.system.diagnostics.AssumptionChecks
import org.openax12.feature.system.diagnostics.CurveProbe
import org.openax12.feature.system.diagnostics.CurveProbeRow
import org.openax12.feature.system.diagnostics.DiagnosticsData
import org.openax12.feature.system.diagnostics.DiagnosticsReport
import org.openax12.feature.system.diagnostics.Verdict
import org.openax12.feature.system.elrs.CommandProgress
import org.openax12.feature.system.elrs.ElrsTree
import org.openax12.feature.system.elrs.commandProgress
import org.openax12.feature.system.preflight.ChecklistOps
import org.openax12.feature.system.preflight.preFlightRows
import org.openax12.feature.system.settings.RadioAction
import org.openax12.feature.system.settings.languageOptions
import org.openax12.feature.system.settings.needsConfirmation
import org.openax12.feature.system.simulator.decimalsFor
import org.openax12.feature.system.simulator.scaleValue
import org.openax12.feature.system.simulator.springsBack
import org.openax12.feature.system.simulator.switchIndex
import org.openax12.feature.system.simulator.unscaleValue

class PureLogicTest {

    // -- ELRS tree ---------------------------------------------------------------------------

    private val items = listOf(
        ElrsMenuItem(1, 0, 0, ElrsItemType.TEXT_SELECTION, name = "Rate", options = listOf("a", "b")),
        ElrsMenuItem(2, 0, 0, ElrsItemType.FOLDER, name = "Power"),
        ElrsMenuItem(3, 2, 0, ElrsItemType.FOLDER, name = "Dynamic"),
        ElrsMenuItem(4, 3, 5, ElrsItemType.UINT8, name = "Level"),
        ElrsMenuItem(5, 0, 0, ElrsItemType.INFO, name = "Hidden", visible = false),
    )

    @Test
    fun elrsChildrenAreTheVisibleItemsOfAFolder() {
        assertEquals(listOf(1, 2), ElrsTree.children(items, ElrsTree.ROOT).map { it.index })
        assertEquals(listOf(3), ElrsTree.children(items, 2).map { it.index })
    }

    @Test
    fun elrsPathRunsFromTheRootDownAndSurvivesCycles() {
        assertEquals(listOf("Power", "Dynamic"), ElrsTree.path(items, 3).map { it.name })
        assertEquals(2, ElrsTree.parentOf(items, 3))
        assertEquals(ElrsTree.ROOT, ElrsTree.parentOf(items, 99))
        val loop = listOf(
            ElrsMenuItem(1, 2, 0, ElrsItemType.FOLDER, name = "A"),
            ElrsMenuItem(2, 1, 0, ElrsItemType.FOLDER, name = "B"),
        )
        assertEquals(2, ElrsTree.path(loop, 1).size)
    }

    @Test
    fun elrsUpsertReplacesByIndexOrAppends() {
        val replaced = ElrsTree.upsert(items, items[0].copy(value = 1))
        assertEquals(items.size, replaced.size)
        assertEquals(1, replaced[0].value)
        assertEquals(items.size + 1, ElrsTree.upsert(items, ElrsMenuItem(9, 0)).size)
    }

    @Test
    fun elrsNumericRangesAndEditability() {
        assertEquals(0..255, ElrsTree.numericRange(ElrsItemType.UINT8))
        assertEquals(-128..127, ElrsTree.numericRange(ElrsItemType.INT8))
        assertNull(ElrsTree.numericRange(ElrsItemType.FOLDER))
        assertTrue(ElrsTree.isEditable(items[0]))
        assertFalse(ElrsTree.isEditable(items[1]))
    }

    @Test
    fun commandAcknowledgementsMapToProgress() {
        assertEquals(CommandProgress.Running("x"), commandProgress(ElrsAck(status = 2, options = "x")))
        assertEquals(CommandProgress.NeedsConfirmation("sure?"), commandProgress(ElrsAck(status = 3, options = "sure?")))
        assertEquals(CommandProgress.Idle, commandProgress(ElrsAck(status = 0)))
    }

    // -- Calibration extents -----------------------------------------------------------------

    @Test
    fun stickExtentsTrackMinimumAndMaximumAndReportCompletion() {
        var extents = StickExtents()
        fun frame(rud: Int, ele: Int, thr: Int, ail: Int) = LiveSnapshot.of(
            logic = mapOf(SourceId.RUD to rud, SourceId.ELE to ele, SourceId.THR to thr, SourceId.AIL to ail),
        )
        extents = extents.observe(frame(0, 0, -100, 0))
        extents = extents.observe(frame(100, 100, 100, 100))
        assertFalse(extents.isComplete)
        extents = extents.observe(frame(-100, -100, -100, -100))
        assertTrue(extents.isComplete)
        assertEquals(AxisExtent(-100, 100), extents.of(SourceId.RUD))
    }

    @Test
    fun observingAnUnchangedFrameReturnsTheSameInstance() {
        val frame = LiveSnapshot.of(logic = mapOf(SourceId.RUD to 10))
        val first = StickExtents().observe(frame)
        assertSame(first, first.observe(frame))
    }

    // -- Pre-flight --------------------------------------------------------------------------

    @Test
    fun preFlightRowsListPresetsAndFailingSources() {
        val system = SystemConfig().withPreFlightPreset(SourceId.THR, -100)
        val sources = listOf(SourceInfo(index = Sources.THR, name = "Throttle"))
        val states = List(36) { if (it == Sources.SA) 1 else 0 }
        val rows = preFlightRows(system, sources, states)
        assertEquals(listOf(Sources.THR, Sources.SA), rows.map { it.source.index })
        assertEquals("Throttle", rows[0].name)
        assertFalse(rows[0].failing)
        assertTrue(rows[1].failing)
        assertNull(rows[1].preset)
        assertTrue(preFlightRows(SystemConfig(), emptyList(), null).isEmpty())
    }

    @Test
    fun checklistEditsAreSafeAtTheBorders() {
        var list = listOf(ChecklistItem("a"), ChecklistItem("b"), ChecklistItem("c"))
        assertEquals(list, ChecklistOps.add(list, "  "))
        assertEquals(listOf("b", "a", "c"), ChecklistOps.move(list, 0, 1).map { it.text })
        assertEquals(list, ChecklistOps.move(list, 0, -1))
        assertEquals(listOf("a", "b", "c"), ChecklistOps.move(list, 5, 1).map { it.text })
        list = ChecklistOps.rename(list, 1, " bee ")
        assertEquals("bee", list[1].text)
        assertEquals(list, ChecklistOps.rename(list, 1, ""))
        list = ChecklistOps.toggleSpoken(list, 2)
        assertEquals(listOf("c"), ChecklistOps.spokenTexts(list))
        assertEquals(2, ChecklistOps.remove(list, 0).size)
        assertEquals(3, ChecklistOps.remove(list, 9).size)
    }

    // -- Settings ----------------------------------------------------------------------------

    @Test
    fun radioActionsAskWhileLinkedAndRestoreAlwaysAsks() {
        val stick = RadioAction.SetStickMode(StickMode.MODE_1)
        assertFalse(stick.needsConfirmation(linkUp = false))
        assertTrue(stick.needsConfirmation(linkUp = true))
        assertTrue(RadioAction.RestoreDefaults.needsConfirmation(linkUp = false))
        assertTrue(RadioAction.RebootRf.needsConfirmation(linkUp = true))
    }

    @Test
    fun speechLanguagesStartWithTheSystemDefaultAndKeepTheConfiguredOne() {
        assertEquals(listOf("", "de-DE", "en-US"), languageOptions("", listOf("en-US", "de-DE", "en-US")))
        assertEquals(listOf("", "en-US", "fr-FR"), languageOptions("fr-FR", listOf("en-US")))
    }

    // -- Simulator ---------------------------------------------------------------------------

    @Test
    fun simulatorHelpers() {
        assertEquals(0, switchIndex(-100))
        assertEquals(1, switchIndex(0))
        assertEquals(2, switchIndex(100))
        assertTrue(springsBack(SourceId.AIL))
        assertFalse(springsBack(SourceId.THR))
        assertEquals(5, decimalsFor(SensorId("crsf.gps.lat")))
        assertEquals(1, decimalsFor(SensorId("crsf.vspd")))
        assertEquals(52_52000, scaleValue(52.52, 5))
        assertEquals(1.8, unscaleValue(scaleValue(1.8, 1), 1), 1e-9)
        assertEquals(18_000_000, scaleValue(1e12, 1))
    }

    // -- Formatting --------------------------------------------------------------------------

    @Test
    fun formatting() {
        assertEquals("812 B", formatBytes(812))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("01.02.0003", formatFirmwareVersion(0x01020003))
        assertEquals("01.02", formatHardwareVersion(0x0102))
        assertEquals("12 h 05 min", formatDuration(12 * 3600L + 5 * 60))
        assertEquals("38 s", formatDuration(38))
    }

    // -- Assumption checks -------------------------------------------------------------------

    private fun verdict(number: Int, data: DiagnosticsData) = AssumptionChecks.evaluate(data)[number - 1].verdict

    @Test
    fun everyAssumptionHasACheck() {
        val checks = AssumptionChecks.evaluate(DiagnosticsData())
        assertEquals(AssumptionChecks.COUNT, checks.size)
        assertEquals((1..AssumptionChecks.COUNT).toList(), checks.map { it.number })
    }

    @Test
    fun switchValuesOutsideTheDocumentedSetAreSuspicious() {
        assertEquals(Verdict.NO_DATA, verdict(3, DiagnosticsData()))
        assertEquals(Verdict.OK, verdict(3, DiagnosticsData(observedSwitches = mapOf(Sources.SA to setOf(-100, 0, 100)))))
        assertEquals(Verdict.SUSPICIOUS, verdict(3, DiagnosticsData(observedSwitches = mapOf(Sources.SA to setOf(-100, 50)))))
    }

    @Test
    fun connectionErrorsMakeTheSerialPortChecksSuspicious() {
        val error = DiagnosticsData(connection = ConnectionState.Error(ConnectionError.DeviceTimeout()))
        assertEquals(Verdict.SUSPICIOUS, verdict(1, error))
        assertEquals(Verdict.SUSPICIOUS, verdict(24, error))
        assertEquals(Verdict.OK, verdict(1, DiagnosticsData(connection = ConnectionState.Ready)))
    }

    @Test
    fun curveProbeDistinguishesPercentFromHalfScale() {
        fun probe(device: (Double) -> Double) = CurveProbe(
            curveIndex = 0,
            type = CurveType.EXPO,
            rows = CurveProbe.INPUTS.map { CurveProbeRow(it, device(it.toDouble()), it * 0.6) },
        )
        val percent = DiagnosticsData(curveProbe = probe { it * 0.6 })
        assertEquals(Verdict.OK, verdict(19, percent))
        assertEquals(Verdict.OK, verdict(12, percent))
        val half = DiagnosticsData(curveProbe = probe { it * 0.3 })
        assertEquals(Verdict.SUSPICIOUS, verdict(19, half))
        assertTrue(AssumptionChecks.evaluate(half)[18].lines.any { it.contains("half-scale") })
        assertEquals(Verdict.NO_DATA, verdict(19, DiagnosticsData()))
    }

    @Test
    fun currentModelIndexNeedsExactlyOneMatchingInterpretation() {
        val models = listOf(ModelInfo(name = "A", index = 1001), ModelInfo(name = "B", index = 1002))
        assertEquals(Verdict.OK, verdict(15, DiagnosticsData(models = models, currentModelRaw = 1002)))
        assertEquals(Verdict.OK, verdict(15, DiagnosticsData(models = models, currentModelRaw = 1)))
        assertEquals(Verdict.INFO, verdict(15, DiagnosticsData(models = models.take(1), currentModelRaw = 0)))
        assertEquals(Verdict.NO_DATA, verdict(15, DiagnosticsData(models = models, currentModelRaw = null)))
    }

    @Test
    fun speechAndCrsfChecks() {
        assertEquals(Verdict.SUSPICIOUS, verdict(9, DiagnosticsData(tts = TtsStatus.NoEngine)))
        assertEquals(Verdict.OK, verdict(9, DiagnosticsData(tts = TtsStatus.Ready("e", "en-US"))))
        assertEquals(Verdict.NO_DATA, verdict(7, DiagnosticsData()))
        val crsf = org.openax12.core.data.CrsfStats(totalFrames = 10, framesByType = mapOf(0x07 to 5L, 0x09 to 5L))
        assertEquals(Verdict.OK, verdict(7, DiagnosticsData(crsf = crsf)))
    }

    @Test
    fun reportContainsTheRawValuesAndTheChecks() {
        val data = DiagnosticsData(
            capturedMs = 0,
            sdkVersion = "build-123",
            system = SystemConfig(alarmVoltageDeciVolt = 72),
            connection = ConnectionState.Ready,
        )
        val report = DiagnosticsReport.build(data, AssumptionChecks.evaluate(data), "1.0")
        assertTrue(report.contains("sdk=build-123"))
        assertTrue(report.contains("alarmVoltageDeciVolt=72"))
        assertTrue(report.contains("#3 NO_DATA"))
        assertTrue(report.contains("[crsf]"))
    }

    @Test
    fun categoriesHaveLabels() {
        AudioCategory.entries.forEach { org.openax12.feature.system.settings.categoryLabel(it) }
    }
}
