package org.openax12.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun <T : Any> assertNotNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}

/** The values of the model file read from the AX12 on 2026-11-25 (the radio's real settings). */
internal fun sampleModelFile(
    rf: Int = 0,
    alarm: Int = 33,
    stick: Int = 1,
    baud: Int = 170,
    fsMode: Int = 0,
    modelMatch: Int = 0,
    extra: String = "",
): String {
    val zeros = List(16) { 0 }.joinToString(",")
    val noCheck = List(36) { 127 }.joinToString(",")
    return """
        {
          "MODEL_FILE_CFG": {"fileFlag": 305419896, "modelIdx": 1795645976, "modelName": "Model-880", "timeStamp": 1795647339},
          "RC_MODEL_CFG": {
            "alramVtg": $alarm,
            "counter": [{"beepTimeSec": 170, "startTime": -1431655766, "threshold": -1431655766}],
            "crsfBaudRate": $baud,
            "flag": 184,
            "fsChVal": [$zeros],
            "fsMode": $fsMode,
            "joytickBeep": 0,
            "modelMatchId": $modelMatch,
            "preFlightCheckChannel": [$noCheck],
            "rfModuleMode": $rf,
            "stickMode": $stick
            $extra
          },
          "channelOut": [{"chCfg": {"curveIdx": 0}}],
          "notifyCfg": []
        }
    """.trimIndent()
}

class ModelConfigFileTest {

    @Test
    fun `reads the radio-wide fields of a real model file`() {
        val fields = assertNotNull(ModelConfigFile.readSystem(sampleModelFile()))
        assertEquals(0, fields.rfModuleMode)
        assertEquals(33, fields.alarmVoltageDeciVolt)
        assertEquals(1, fields.stickMode)
        assertEquals(170, fields.crsfBaudRate)
        assertEquals(0, fields.failsafeMode)
        assertEquals(0, fields.modelMatchId)
        assertEquals(16, fields.failsafeValues.size)
        assertEquals(36, fields.preFlightCheck.size)
        assertNull(SystemConfigValidity.problem(fields))
    }

    @Test
    fun `a file without a usable section reads as null`() {
        assertNull(ModelConfigFile.readSystem("not json"))
        assertNull(ModelConfigFile.readSystem("[]"))
        assertNull(ModelConfigFile.readSystem("""{"MODEL_FILE_CFG": {}}"""))
        // A missing key is as bad as a missing section: nothing is guessed.
        assertNull(ModelConfigFile.readSystem(sampleModelFile().replace("\"alramVtg\"", "\"alarm\"")))
        // A string is not a number.
        assertNull(ModelConfigFile.readSystem(sampleModelFile().replace("\"rfModuleMode\": 0", "\"rfModuleMode\": \"0\"")))
    }

    @Test
    fun `patching replaces only the named fields and keeps the rest of the document`() {
        val original = sampleModelFile(modelMatch = 7)
        val patched = assertNotNull(ModelConfigFile.patch(original, RadioWidePatch(alarmVoltageDeciVolt = 34)))
        val fields = assertNotNull(ModelConfigFile.readSystem(patched))
        assertEquals(34, fields.alarmVoltageDeciVolt)
        assertEquals(0, fields.rfModuleMode)
        assertEquals(1, fields.stickMode)
        assertEquals(170, fields.crsfBaudRate)
        assertEquals(7, fields.modelMatchId)

        val before = Json.parseToJsonElement(original).jsonObject
        val after = Json.parseToJsonElement(patched).jsonObject
        assertEquals(before["MODEL_FILE_CFG"], after["MODEL_FILE_CFG"])
        assertEquals(before["channelOut"], after["channelOut"])
        assertEquals(before[ModelConfigFile.CONFIG_KEY]!!.jsonObject["counter"], after[ModelConfigFile.CONFIG_KEY]!!.jsonObject["counter"])
        // The unchanged document survives a patch that changes nothing.
        assertEquals(before, Json.parseToJsonElement(assertNotNull(ModelConfigFile.patch(original, RadioWidePatch()))).jsonObject)
    }

    @Test
    fun `all three radio-wide fields can be patched together`() {
        val patched = assertNotNull(
            ModelConfigFile.patch(sampleModelFile(), RadioWidePatch(stickMode = 0, rfModuleMode = 1, alarmVoltageDeciVolt = 70)),
        )
        val fields = assertNotNull(ModelConfigFile.readSystem(patched))
        assertEquals(0, fields.stickMode)
        assertEquals(1, fields.rfModuleMode)
        assertEquals(70, fields.alarmVoltageDeciVolt)
    }

    @Test
    fun `an implausible patch or a broken file is refused untouched`() {
        val file = sampleModelFile()
        assertNull(ModelConfigFile.patch(file, RadioWidePatch(stickMode = 2)))
        assertNull(ModelConfigFile.patch(file, RadioWidePatch(rfModuleMode = 3)))
        assertNull(ModelConfigFile.patch(file, RadioWidePatch(alarmVoltageDeciVolt = 256)))
        assertNull(ModelConfigFile.patch(file, RadioWidePatch(alarmVoltageDeciVolt = -1)))
        assertNull(ModelConfigFile.patch("{}", RadioWidePatch(alarmVoltageDeciVolt = 34)))
        assertNull(ModelConfigFile.patch("garbage", RadioWidePatch(alarmVoltageDeciVolt = 34)))
    }

    @Test
    fun `differingFrom keeps only the fields that change`() {
        val fields = assertNotNull(ModelConfigFile.readSystem(sampleModelFile()))
        val wanted = RadioWidePatch(stickMode = 1, rfModuleMode = 2, alarmVoltageDeciVolt = 33)
        assertEquals(RadioWidePatch(rfModuleMode = 2), wanted.differingFrom(fields))
        assertTrue(wanted.differingFrom(fields.copy(rfModuleMode = 2)).isEmpty)
        assertEquals(fields.radioWide, RadioWidePatch(stickMode = 1, rfModuleMode = 0, alarmVoltageDeciVolt = 33))
    }
}
