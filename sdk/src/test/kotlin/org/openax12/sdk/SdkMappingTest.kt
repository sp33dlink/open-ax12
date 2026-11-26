package org.openax12.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.CompareOp
import org.openax12.core.model.LiveSnapshot
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.RawSystemFields
import org.openax12.core.model.SourceId
import org.openax12.core.model.StickMode
import org.openax12.core.model.SystemConfigValidity
import org.openax12.core.radio.RadioEvent

class SdkMappingTest {

    private val counts = SdkCounts(sourceCount = 42, channelCount = 16, curveCount = 18, mixCounts = IntArray(16) { 2 })

    // ---------------------------------------------------------------------------------------------
    // Index handling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `reported indices are used only when they form a permutation`() {
        assertArrayEquals(intArrayOf(2, 0, 1), SdkIndex.resolve(intArrayOf(2, 0, 1)))
        assertArrayEquals(intArrayOf(0, 1, 2), SdkIndex.resolve(intArrayOf(0, 0, 0)))
        assertArrayEquals(intArrayOf(0, 1, 2), SdkIndex.resolve(intArrayOf(1, 2, 3)))
        assertArrayEquals(intArrayOf(0, 1), SdkIndex.resolve(intArrayOf(0, -1)))
        assertArrayEquals(IntArray(0), SdkIndex.resolve(IntArray(0)))
    }

    @Test
    fun `write keys fall back to positions when the reported index is ambiguous`() {
        assertArrayEquals(intArrayOf(5, 7), SdkIndex.keys(intArrayOf(5, 7)))
        assertArrayEquals(intArrayOf(0, 1), SdkIndex.keys(intArrayOf(0, 0)))
    }

    @Test
    fun `counts are decoded and capped`() {
        val buffer = IntArray(NativeLayout.COUNTS_SIZE)
        buffer[NativeLayout.COUNTS_SOURCE_COUNT] = 1000
        buffer[NativeLayout.COUNTS_CHANNEL_COUNT] = 2
        buffer[NativeLayout.COUNTS_CURVE_COUNT] = 18
        // channel 1 reported first, with 3 mixes; channel 0 second, with 5
        buffer[NativeLayout.COUNTS_CHANNELS_OFFSET] = 1
        buffer[NativeLayout.COUNTS_CHANNELS_OFFSET + 1] = 3
        buffer[NativeLayout.COUNTS_CHANNELS_OFFSET + NativeLayout.COUNTS_CHANNEL_STRIDE] = 0
        buffer[NativeLayout.COUNTS_CHANNELS_OFFSET + NativeLayout.COUNTS_CHANNEL_STRIDE + 1] = 5
        val decoded = SdkCounts.decode(buffer)
        assertEquals(NativeLayout.LIVE_MAX_SOURCES, decoded.sourceCount)
        assertEquals(2, decoded.channelCount)
        assertEquals(5, decoded.mixCount(0))
        assertEquals(3, decoded.mixCount(1))
        assertEquals(0, decoded.mixCount(2))
        assertTrue(decoded.isMix(0, 4))
        assertFalse(decoded.isMix(0, 5))
        assertFalse(decoded.isMix(-1, 0))
        assertTrue(decoded.isCurve(17))
        assertFalse(decoded.isCurve(18))
        assertTrue(decoded.isCurveReference(NO_CURVE))
        assertEquals(SdkCounts.EMPTY.channelCount, SdkCounts.decode(IntArray(3)).channelCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Read-modify-write
    // ---------------------------------------------------------------------------------------------

    private fun rawSysCfg() = RawSysCfg().apply {
        fsMode = 3
        stickMode = 3 // not a documented code
        rfModuleMode = 0xFF
        alramVtg = 100
        preFlightCheckChannel.fill(127)
    }

    @Test
    fun `an unknown device code survives a write that changes something else`() {
        val raw = rawSysCfg()
        val config = SdkMapping.systemConfig(raw).copy(alarmVoltageDeciVolt = 105)
        assertTrue(SdkMapping.overlaySystemConfig(raw, config))
        assertEquals(3, raw.stickMode)
        assertEquals(0xFF, raw.rfModuleMode)
        assertEquals(105, raw.alramVtg)
        assertTrue(SdkMapping.overlaySystemConfig(raw, config.copy(stickMode = StickMode.MODE_1)))
        assertEquals(0, raw.stickMode)
    }

    @Test
    fun `the system struct of a model file round-trips and a change keeps the other radio values`() {
        val fields = RawSystemFields(
            failsafeMode = 0,
            failsafeValues = List(16) { 0 },
            stickMode = 1,
            preFlightCheck = List(36) { 127 },
            rfModuleMode = 0,
            alarmVoltageDeciVolt = 33,
            stickBeep = 0,
            modelMatchId = 0,
            crsfBaudRate = 170,
        )
        val raw = SdkMapping.rawSysCfg(fields)
        assertEquals(fields, SdkMapping.rawSystemFields(raw))
        assertEquals(null, SystemConfigValidity.problem(fields))
        // The alarm voltage changes, RF, baud, failsafe and stick mode stay exactly as read.
        assertTrue(SdkMapping.overlaySystemConfig(raw, SdkMapping.systemConfig(raw).copy(alarmVoltageDeciVolt = 34)))
        assertEquals(fields.copy(alarmVoltageDeciVolt = 34), SdkMapping.rawSystemFields(raw))
    }

    @Test
    fun `a rejected system config leaves the struct untouched`() {
        val raw = rawSysCfg()
        val config = SdkMapping.systemConfig(raw)
        assertFalse(SdkMapping.overlaySystemConfig(raw, config.copy(alarmVoltageDeciVolt = 256)))
        assertFalse(SdkMapping.overlaySystemConfig(raw, config.copy(failsafeValues = config.failsafeValues.toMutableList().also { it[0] = 200 })))
        assertFalse(SdkMapping.overlaySystemConfig(raw, config.copy(preFlightCheck = config.preFlightCheck.dropLast(1))))
        assertEquals(100, raw.alramVtg)
        assertEquals(0, raw.fsChVal[0])
    }

    @Test
    fun `channel overlay keeps read-only fields and unknown lock codes`() {
        val raw = RawChOut().apply {
            chIdx = 3
            mixesNum = 4
            chOut = 17
            logicVal = 0.5f
            lockChSwIdx = 22
            lockChSwType = 9 // unknown compare code
            dr2Weight = 40
        }
        val config = SdkMapping.outputChannel(raw, 3).copy(weight = -80, min = -90)
        assertTrue(SdkMapping.overlayChannel(raw, config, counts))
        assertEquals(-80, raw.weight)
        assertEquals(-90, raw.min)
        assertEquals(9, raw.lockChSwType)
        assertEquals(40, raw.dr2Weight)
        assertEquals(4, raw.mixesNum)
        assertEquals(17, raw.chOut)
        assertEquals(CompareOp.DISABLED, SdkMapping.outputChannel(raw, 3).lock.op)
    }

    @Test
    fun `a changed index must exist but an unchanged one may stay`() {
        val raw = RawChOut().apply { curveIdx = 200; lockChSwIdx = 99 }
        val unchanged = SdkMapping.outputChannel(raw, 0)
        assertTrue(SdkMapping.overlayChannel(raw, unchanged, counts))
        assertFalse(SdkMapping.overlayChannel(raw, unchanged.copy(curve = 18), counts))
        assertFalse(SdkMapping.overlayChannel(raw, unchanged.copy(curve = NO_CURVE, lock = unchanged.lock.copy(source = SourceId(42))), counts))
        assertTrue(SdkMapping.overlayChannel(raw, unchanged.copy(curve = NO_CURVE), counts))
        assertEquals(NO_CURVE, raw.curveIdx)
    }

    @Test
    fun `mix overlay validates sources and curves before touching the struct`() {
        val raw = RawMix().apply { srcIdx = 1; curveIdx = NO_CURVE; mixesMode = 7; chIdx = 2; mixIdx = 1; mixesCount = 2; srcName = "Ele" }
        val line = SdkMapping.mixLine(raw, 2, 1)
        assertFalse(SdkMapping.overlayMix(raw, line.copy(source = SourceId(42)), counts))
        assertFalse(SdkMapping.overlayMix(raw, line.copy(slowUpMs = -1), counts))
        assertEquals(1, raw.srcIdx)
        assertTrue(SdkMapping.overlayMix(raw, line.copy(weightUp = 55), counts))
        assertEquals(55, raw.weightUp)
        assertEquals(7, raw.mixesMode) // unknown mix mode preserved
        assertEquals("Ele", raw.srcName)
        assertEquals(2, raw.mixesCount)
    }

    @Test
    fun `curve overlay refuses arrays and point counts that do not fit`() {
        val raw = RawCurve().apply { idx = 4; cType = 4; ptNum = 3 }
        val curve = SdkMapping.curve(raw, 4)
        assertFalse(SdkMapping.overlayCurve(raw, curve.copy(pointCount = 13)))
        assertFalse(SdkMapping.overlayCurve(raw, curve.copy(x = List(13) { 0 })))
        assertTrue(SdkMapping.overlayCurve(raw, curve.copy(pointCount = 12)))
        assertEquals(12, raw.ptNum)
        assertEquals(12, raw.xVal.size)
    }

    @Test
    fun `model structs reject values the SDK cannot represent`() {
        val info = SdkMapping.modelInfo(RawModel().apply { modelName = "A"; filePath = "/x/a.json"; modelMatch = 0xFFFF_FFFFL })
        assertNotNull(SdkMapping.rawModel(info))
        assertNull(SdkMapping.rawModel(info.copy(modelMatch = 0x1_0000_0000L)))
        assertNull(SdkMapping.rawModel(info.copy(filePath = " ")))
        assertNull(SdkMapping.rawModel(info.copy(name = "a\u0000b")))
    }

    // ---------------------------------------------------------------------------------------------
    // Live frames and messages
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a live frame owns its arrays`() {
        val ints = IntArray(NativeLayout.LIVE_INT_SIZE)
        val floats = FloatArray(NativeLayout.LIVE_FLOAT_SIZE)
        ints[NativeLayout.LIVE_INT_SOURCE_COUNT] = 2
        ints[NativeLayout.LIVE_INT_CHANNEL_COUNT] = 1
        // sources reported in swapped order: srcIdx 1 first
        ints[NativeLayout.LIVE_INT_SOURCES_OFFSET] = 1
        ints[NativeLayout.LIVE_INT_SOURCES_OFFSET + 1] = 150 // rawVal, 30 %
        ints[NativeLayout.LIVE_INT_SOURCES_OFFSET + NativeLayout.LIVE_SOURCE_STRIDE] = 0
        ints[NativeLayout.LIVE_INT_SOURCES_OFFSET + NativeLayout.LIVE_SOURCE_STRIDE + 1] = -200 // rawVal, -40 %
        ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET + 1] = 1775 // PWM µs, 55 %
        floats[NativeLayout.LIVE_FLOAT_CHANNELS_OFFSET] = 1.5f
        val decoder = LiveFrameDecoder()
        val first = decoder.decode(ints, floats, 10L, LiveSnapshot.DEFAULT_TIMERS, LiveSnapshot.DEFAULT_COUNTERS)!!
        ints.fill(99)
        floats.fill(9f)
        val second = decoder.decode(ints, floats, 20L, LiveSnapshot.DEFAULT_TIMERS, LiveSnapshot.DEFAULT_COUNTERS)
        assertEquals(30, first.logicAt(1))
        assertEquals(-40, first.logicAt(0))
        assertEquals(55, first.channel(0))
        assertEquals(1.5f, first.channelLogic(0), 0f)
        assertEquals(2, first.sourceCount)
        assertNotNull(second)
        assertTrue(second!!.sequence > first.sequence)
        assertEquals(30, first.logicAt(1))
    }

    @Test
    fun `messages with a missing or short payload are dropped`() {
        assertNull(SdkMessageDecoder.decode(SdkMsg.TIM_REPORT, intArrayOf(1, 2, 3), null, null, null))
        assertNull(SdkMessageDecoder.decode(SdkMsg.TIM_NOTIFY, null, null, null, null))
        assertNull(SdkMessageDecoder.decode(SdkMsg.ELRS_TELEMETRY, null, DoubleArray(6), null, null))
        assertNull(SdkMessageDecoder.decode(SdkMsg.ELRS_MENUITEM_UPDATE, IntArray(6), null, null, arrayOf("a", "b")))
        assertNull(SdkMessageDecoder.decode(SdkMsg.CRSF_DATA_RECEIVED, null, null, ByteArray(0), null))
        assertNull(SdkMessageDecoder.decode(SdkMsg.SRCINPUT_UPDATE, null, null, null, null))
        assertNull(SdkMessageDecoder.decode(0x7fffffff, null, null, null, null))
    }

    @Test
    fun `messages are decoded by id`() {
        assertEquals(SdkMessage.DeviceConfigRead, SdkMessageDecoder.decode(SdkMsg.GETDEVCFG_END, null, null, null, null))
        val report = SdkMessageDecoder.decode(SdkMsg.CNT_REPORT, intArrayOf(2, 1, 9, 4), null, null, null) as SdkMessage.TimerReported
        assertTrue(report.isCounter)
        assertEquals(2, report.report.index)
        assertTrue(report.report.showsElapsed)
        assertEquals(4, report.report.count)
        val trim = SdkMessageDecoder.decode(SdkMsg.TRIM_NOTIFY, null, null, null, null) as SdkMessage.Event
        assertTrue(trim.event is RadioEvent.TrimChanged)
        val item = SdkMessageDecoder.decode(
            SdkMsg.ELRS_MENUITEM_UPDATE,
            intArrayOf(3, 1, 9, 0, 1, 0),
            null,
            null,
            arrayOf("Packet Rate", "ELRS", "Hz", "50", "100"),
        ) as SdkMessage.ElrsMenuItemReceived
        assertEquals(3, item.item.index)
        assertEquals(listOf("50", "100"), item.item.options)
        assertFalse(item.item.visible)
    }
}
