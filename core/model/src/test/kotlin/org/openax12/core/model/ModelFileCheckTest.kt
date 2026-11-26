package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A complete SDK model file in the format the AX12 writes (shortened to three channels). */
private fun completeFile(
    channelOut: String = """
        [
          {"chIdx":0,"chCfg":{"curveIdx":0,"max":100,"middle":0,"min":-100,"mixesNum":1,"weight":100},
           "mixCfg":[{"curveIdx":255,"mixesMode":1,"offset":0,"srcIdx":3,"weightDown":100,"weightUp":100}]},
          {"chIdx":1,"chCfg":{"curveIdx":0,"max":100,"middle":0,"min":-100,"mixesNum":2,"weight":-100},
           "mixCfg":[{"offset":0,"srcIdx":1,"weightDown":80,"weightUp":80},{"offset":5,"srcIdx":4,"weightDown":50,"weightUp":50}]},
          {"chIdx":2,"chCfg":{"max":100,"middle":0,"min":-100,"mixesNum":0,"weight":100},"mixCfg":[]}
        ]
    """.trimIndent(),
    rc: Boolean = true,
): String = buildString {
    append("""{"MODEL_FILE_CFG":{"fileFlag":305419896,"modelName":"Model-880"},""")
    if (rc) append(""""RC_MODEL_CFG":{"stickMode":1},""")
    append(""""channelOut":""").append(channelOut).append("""}""")
}

private fun channel(index: Int, min: Int = -100, max: Int = 100, weight: Int = 100, mixCount: Int = 0) =
    OutputChannelConfig(channel = index, min = min, max = max, weight = weight, mixCount = mixCount)

private fun mix(channel: Int, index: Int, source: Int, weight: Int, offset: Int = 0) = MixLine(
    channel = channel,
    mixIndex = index,
    source = SourceId(source),
    weightUp = weight,
    weightDown = weight,
    offset = offset,
)

/** The working config that equals [completeFile]. */
private fun matchingWorking() = WorkingConfig(
    channels = listOf(channel(0, mixCount = 1), channel(1, weight = -100, mixCount = 2), channel(2)),
    mixes = listOf(mix(0, 0, 3, 100), mix(1, 0, 1, 80), mix(1, 1, 4, 50, offset = 5)),
)

class ModelFileCheckTest {

    @Test
    fun `a complete file has no problem and is summarised`() {
        assertNull(ModelFileCheck.fileProblem(completeFile()))
        val summary = ModelFileCheck.summarize(completeFile())
        assertNotNull(summary)
        assertEquals(3, summary!!.channels.size)
        assertEquals(3, summary.mixCount)
        assertEquals(-100, summary.channels[1].weight)
    }

    @Test
    fun `a file without channelOut is the r11 case and is refused`() {
        val text = """{"MODEL_FILE_CFG":{"fileFlag":305419896},"RC_MODEL_CFG":{"stickMode":1}}"""
        assertTrue(ModelFileCheck.fileProblem(text)!!.contains("channelOut"))
        assertNull(ModelFileCheck.summarize(text))
    }

    @Test
    fun `an empty or damaged channelOut is refused`() {
        assertNotNull(ModelFileCheck.fileProblem(completeFile(channelOut = "[]")))
        assertNotNull(ModelFileCheck.fileProblem(completeFile(channelOut = """[{"chIdx":0}]""")))
        assertNotNull(ModelFileCheck.fileProblem(completeFile(channelOut = """"text"""")))
        assertNotNull(ModelFileCheck.fileProblem(completeFile(rc = false)))
    }

    @Test
    fun `a channel that declares other mixes than it lists is refused`() {
        val text = completeFile(
            channelOut = """[{"chIdx":0,"chCfg":{"max":100,"middle":0,"min":-100,"mixesNum":2,"weight":100},"mixCfg":[]}]""",
        )
        assertTrue(ModelFileCheck.fileProblem(text)!!.contains("declares 2 mixes"))
    }

    @Test
    fun `limits that are all zero are refused`() {
        val text = completeFile(
            channelOut = """[{"chIdx":0,"chCfg":{"max":0,"middle":0,"min":0,"mixesNum":0,"weight":0},"mixCfg":[]}]""",
        )
        assertTrue(ModelFileCheck.fileProblem(text)!!.contains("limits"))
    }

    @Test
    fun `files that are not in the SDK format pass the file check`() {
        assertNull(ModelFileCheck.fileProblem("""{"modelName":"plain","config":"c"}"""))
        assertNull(ModelFileCheck.fileProblem("not json"))
        assertNull(ModelFileCheck.summarize("""{"modelName":"plain"}"""))
        assertEquals(false, ModelFileCheck.isSdkFormat("""{"modelName":"plain"}"""))
        assertEquals(true, ModelFileCheck.isSdkFormat(completeFile()))
    }

    @Test
    fun `an empty or all-zero working config must not be written`() {
        assertNotNull(ModelFileCheck.workingProblem(WorkingConfig()))
        val zero = WorkingConfig(channels = List(16) { channel(it, min = 0, max = 0, weight = 0) })
        assertNotNull(ModelFileCheck.workingProblem(zero))
        assertNull(ModelFileCheck.workingProblem(matchingWorking()))
        // One channel with limits is a configuration, even if the others are 0.
        assertNull(ModelFileCheck.channelsProblem(listOf(channel(0), channel(1, min = 0, max = 0))))
    }

    @Test
    fun `a working config that equals the file matches`() {
        val summary = ModelFileCheck.summarize(completeFile())!!
        assertNull(ModelFileCheck.mismatch(summary, matchingWorking()))
    }

    @Test
    fun `the empty config an incomplete load leaves is a mismatch`() {
        val summary = ModelFileCheck.summarize(completeFile())!!
        val empty = WorkingConfig(channels = List(3) { channel(it, min = 0, max = 0, weight = 0) })
        val problem = ModelFileCheck.mismatch(summary, empty)!!
        assertTrue(problem, problem.contains("3 mixes in the file, 0 loaded"))
    }

    @Test
    fun `key fields are compared`() {
        val summary = ModelFileCheck.summarize(completeFile())!!
        val base = matchingWorking()

        assertNotNull(ModelFileCheck.mismatch(summary, base.copy(channels = base.channels.drop(1))))
        assertNotNull(ModelFileCheck.mismatch(summary, base.copy(channels = base.channels.map { if (it.channel == 2) it.copy(max = 90) else it })))
        assertNotNull(ModelFileCheck.mismatch(summary, base.copy(mixes = base.mixes.dropLast(1))))
        assertNotNull(ModelFileCheck.mismatch(summary, base.copy(mixes = base.mixes.map { if (it.channel == 1 && it.mixIndex == 1) it.copy(weightUp = 51) else it })))
        assertNotNull(ModelFileCheck.mismatch(summary, base.copy(mixes = base.mixes.map { if (it.channel == 0) it.copy(source = SourceId(9)) else it })))
    }

    @Test
    fun `an import keeps the target's identity and takes the source's channels`() {
        val target = """{"MODEL_FILE_CFG":{"fileFlag":305419896,"modelIdx":113,"modelName":"Copy"},""" +
            """"RC_MODEL_CFG":{"stickMode":0},"channelOut":[{"chIdx":0,"chCfg":{"min":0,"max":0,"middle":0,"weight":0,"mixesNum":0}}]}"""

        val merged = ModelFileCheck.importInto(completeFile(), target)!!

        assertNull(ModelFileCheck.fileProblem(merged))
        assertEquals(3, ModelFileCheck.summarize(merged)!!.channels.size)
        assertEquals(3, ModelFileCheck.summarize(merged)!!.mixCount)
        assertTrue(merged.contains(""""modelIdx":113"""))
        assertTrue(merged.contains(""""modelName":"Copy""""))
        assertTrue(merged.contains(""""stickMode":1"""))
    }

    @Test
    fun `an import of an incomplete or foreign file writes nothing`() {
        val target = """{"MODEL_FILE_CFG":{"modelIdx":113}}"""
        val noChannels = """{"MODEL_FILE_CFG":{"fileFlag":1},"RC_MODEL_CFG":{}}"""

        assertNull(ModelFileCheck.importInto(noChannels, target))
        assertNull(ModelFileCheck.importInto("""{"modelName":"plain"}""", target))
        assertNull(ModelFileCheck.importInto(completeFile(), """{"modelName":"plain"}"""))
        assertNull(ModelFileCheck.importInto(completeFile(), "not json"))
    }

    @Test
    fun `at most a few differences are reported`() {
        val summary = ModelFileCheck.summarize(completeFile())!!
        val zero = WorkingConfig(channels = List(3) { channel(it, min = 0, max = 0, weight = 0) })
        assertTrue(ModelFileCheck.mismatch(summary, zero)!!.split("; ").size <= 4)
    }
}
