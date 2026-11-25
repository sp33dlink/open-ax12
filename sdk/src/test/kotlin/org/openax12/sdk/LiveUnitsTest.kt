package org.openax12.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit conversions measured on the AX12 (2026-11-21), see [SdkAssumptions.sourcePercent]. */
class LiveUnitsTest {

    @Test
    fun `source rawVal is half scale with 500 per 100 percent`() {
        assertEquals(0, SdkAssumptions.sourcePercent(0))
        assertEquals(-100, SdkAssumptions.sourcePercent(-500))
        assertEquals(100, SdkAssumptions.sourcePercent(500))
        assertEquals(60, SdkAssumptions.sourcePercent(300))
        assertEquals(4, SdkAssumptions.sourcePercent(22))
        assertEquals(100, SdkAssumptions.sourcePercent(512))
    }

    @Test
    fun `absent sources read as zero`() {
        assertEquals(0, SdkAssumptions.sourcePercent(SdkAssumptions.SOURCE_ABSENT))
        assertEquals(0, SdkAssumptions.sourceOutputPercent(SdkAssumptions.SOURCE_ABSENT, 100))
        assertEquals(-100, SdkAssumptions.sourceOutputPercent(-500, -100))
    }

    @Test
    fun `channel output is PWM microseconds around 1500`() {
        assertEquals(0, SdkAssumptions.channelPercent(1500))
        assertEquals(-100, SdkAssumptions.channelPercent(1000))
        assertEquals(100, SdkAssumptions.channelPercent(2000))
        assertEquals(3, SdkAssumptions.channelPercent(1515))
        assertEquals(150, SdkAssumptions.channelPercent(2250))
    }

    @Test
    fun `live frame decodes device units into percent`() {
        val ints = IntArray(NativeLayout.LIVE_INT_SIZE)
        val floats = FloatArray(NativeLayout.LIVE_FLOAT_SIZE)
        ints[NativeLayout.LIVE_INT_SOURCE_COUNT] = 3
        ints[NativeLayout.LIVE_INT_CHANNEL_COUNT] = 2
        val src = intArrayOf(0, 250, 628_389_256, 50, 1, SdkAssumptions.SOURCE_ABSENT, 628_389_256, 100, 2, -500, 628_389_256, -100)
        for (p in 0 until 3) for (f in 0 until 4) {
            ints[NativeLayout.LIVE_INT_SOURCES_OFFSET + p * NativeLayout.LIVE_SOURCE_STRIDE + f] = src[p * 4 + f]
        }
        ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET] = 0
        ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET + 1] = 1000
        ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET + NativeLayout.LIVE_CHANNEL_STRIDE] = 1
        ints[NativeLayout.LIVE_INT_CHANNELS_OFFSET + NativeLayout.LIVE_CHANNEL_STRIDE + 1] = 1750
        val frame = LiveFrameDecoder().decode(ints, floats, 0L, emptyList(), emptyList())!!
        assertEquals(50, frame.logicAt(0))
        assertEquals(0, frame.logicAt(1))
        assertEquals(-100, frame.logicAt(2))
        assertEquals(-100, frame.channel(0))
        assertEquals(50, frame.channel(1))
    }
}
