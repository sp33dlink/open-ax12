package org.openax12.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import java.util.Locale

class VolumeModelTest {

    @Test
    fun `everything starts at full volume`() {
        val volumes = VolumeModel()
        for (category in AudioCategory.entries) assertEquals(1f, volumes.gain(category), 0f)
    }

    @Test
    fun `gain is master times category through the taper`() {
        val volumes = VolumeModel()
        volumes.setMaster(50)
        volumes.setCategory(AudioCategory.VARIO, 50)
        assertEquals(0.0625f, volumes.gain(AudioCategory.VARIO), 1e-6f)
        assertEquals(0.25f, volumes.gain(AudioCategory.VOICE), 1e-6f)
        volumes.setCategory(AudioCategory.VOICE, 0)
        assertEquals(0f, volumes.gain(AudioCategory.VOICE), 0f)
    }

    @Test
    fun `override replaces the master volume until removed`() {
        val volumes = VolumeModel()
        volumes.setMaster(20)
        assertTrue(volumes.setOverride(100))
        assertEquals(1f, volumes.gain(AudioCategory.BEEP), 0f)
        assertEquals(100, volumes.effectiveMaster())
        assertTrue(volumes.setOverride(null))
        assertEquals(0.04f, volumes.gain(AudioCategory.BEEP), 1e-6f)
        assertEquals(20, volumes.effectiveMaster())
    }

    @Test
    fun `values are clamped and unchanged values are reported`() {
        val volumes = VolumeModel()
        assertFalse(volumes.setMaster(100))
        assertFalse(volumes.setMaster(250))
        assertTrue(volumes.setMaster(-5))
        assertEquals(0, volumes.effectiveMaster())
        assertTrue(volumes.setCategory(AudioCategory.MUSIC, 130).not())
        assertTrue(volumes.setCategory(AudioCategory.MUSIC, 30))
        assertFalse(volumes.setCategory(AudioCategory.MUSIC, 30))
        assertEquals(30, volumes.category(AudioCategory.MUSIC))
        assertFalse(volumes.setOverride(null))
    }

    @Test
    fun `taper is monotonic and bounded`() {
        var previous = -1f
        for (step in 0..100) {
            val gain = VolumeModel.taper(step / 100f)
            assertTrue(gain > previous)
            previous = gain
        }
        assertEquals(0f, VolumeModel.taper(-1f), 0f)
        assertEquals(1f, VolumeModel.taper(7f), 0f)
        assertEquals(0f, VolumeModel.taper(Float.NaN), 0f)
    }
}

class SpeechLanguageTest {

    private fun tags(language: String, system: Locale) =
        SpeechLanguage.candidates(language, system).map { it.toLanguageTag() }

    @Test
    fun `requested language first, then the system language, then english`() {
        assertEquals(listOf("de-AT", "de", "fr-FR", "fr", "en-US", "en"), tags("de-AT", Locale.FRANCE))
        assertEquals(listOf("de-DE", "de", "en-US", "en"), tags("", Locale.GERMANY))
        assertEquals(listOf("en-US", "en"), tags("en-US", Locale.US))
        assertEquals(listOf("en-GB", "en", "en-US"), tags("en_GB", Locale.US))
    }

    @Test
    fun `nonsense falls back to the system language`() {
        assertEquals(Locale.GERMANY, SpeechLanguage.requestedLocale("   ", Locale.GERMANY))
        assertEquals(Locale.GERMANY, SpeechLanguage.requestedLocale("?!", Locale.GERMANY))
        assertEquals(Locale.forLanguageTag("fr-CA"), SpeechLanguage.requestedLocale(" fr-CA ", Locale.GERMANY))
    }

    @Test
    fun `fallbacks are recognised as such`() {
        val requested = Locale.forLanguageTag("de-AT")
        assertTrue(SpeechLanguage.isRequestedLanguage(Locale.GERMANY, requested))
        assertTrue(SpeechLanguage.isRequestedLanguage(Locale.GERMAN, requested))
        assertFalse(SpeechLanguage.isRequestedLanguage(Locale.US, requested))
    }

    @Test
    fun `three-letter codes of old engines are normalised`() {
        val legacy = Locale.Builder().setLanguage("deu").setRegion("DE").build()
        assertEquals("de-DE", SpeechLanguage.tag(legacy))
        assertTrue(SpeechLanguage.isRequestedLanguage(legacy, Locale.GERMANY))
        assertEquals("en-US", SpeechLanguage.tag(Locale.US))
        assertEquals("en", SpeechLanguage.tag(Locale.ENGLISH))
    }
}

class PatternsTest {

    @Test
    fun `every beep pattern is short and audible`() {
        for (pattern in BeepPattern.entries) {
            val steps = BeepPatterns.steps(pattern)
            assertTrue("$pattern", steps.isNotEmpty())
            assertTrue("$pattern", steps.all { it.frequencyHz in 200..4000 && it.durationMs > 0 && it.pauseMs >= 0 })
            assertTrue("$pattern", steps.sumOf { it.durationMs + it.pauseMs } <= 1000)
            assertEquals("$pattern ends without a pause", 0, steps.last().pauseMs)
        }
    }

    @Test
    fun `trim centre is distinct from a trim step`() {
        val step = BeepPatterns.steps(BeepPattern.TRIM_STEP).single()
        val center = BeepPatterns.steps(BeepPattern.TRIM_CENTER).single()
        assertTrue(center.frequencyHz >= step.frequencyHz * 1.25)
        assertTrue(center.durationMs >= step.durationMs * 2)
        assertNotEquals(BeepPatterns.steps(BeepPattern.TRIM_LIMIT), BeepPatterns.steps(BeepPattern.TRIM_STEP))
    }

    @Test
    fun `haptic patterns are valid waveforms`() {
        for (pattern in HapticPattern.entries) {
            val timings = HapticPatterns.timings(pattern)
            assertEquals("$pattern starts without delay", 0L, timings[0])
            assertEquals("$pattern ends with a vibration", 0, timings.size % 2)
            assertTrue("$pattern", timings.drop(1).all { it > 0 })
            assertTrue("$pattern", HapticPatterns.durationMs(pattern) in 1..2000)
        }
        assertEquals(1, HapticPatterns.timings(HapticPattern.SHORT).size / 2)
        assertEquals(2, HapticPatterns.timings(HapticPattern.DOUBLE).size / 2)
        assertEquals(3, HapticPatterns.timings(HapticPattern.TRIPLE).size / 2)
        assertTrue(HapticPatterns.durationMs(HapticPattern.LONG) > 3 * HapticPatterns.durationMs(HapticPattern.SHORT))
    }
}
