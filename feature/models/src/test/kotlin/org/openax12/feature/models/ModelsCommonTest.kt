package org.openax12.feature.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.data.ModelEntry
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ModelsCommonTest {

    private val zone = ZoneId.of("UTC")
    private fun ms(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // -- names --

    @Test
    fun `valid names pass and problems are reported in order`() {
        val taken = listOf("Swift", "Easy Star")
        assertNull(ModelNameRules.check("Mini Quad", taken))
        assertEquals(NameProblem.EMPTY, ModelNameRules.check("   ", taken))
        assertEquals(NameProblem.TOO_LONG, ModelNameRules.check("x".repeat(33), taken))
        assertEquals(NameProblem.INVALID_CHARACTERS, ModelNameRules.check("a/b", taken))
        assertEquals(NameProblem.INVALID_CHARACTERS, ModelNameRules.check(".hidden", taken))
        assertEquals(NameProblem.INVALID_CHARACTERS, ModelNameRules.check("CON", taken))
        assertEquals(NameProblem.EXISTS, ModelNameRules.check("  swift ", taken))
    }

    @Test
    fun `a model may keep its own name when renamed`() {
        assertNull(ModelNameRules.check("Swift", listOf("Swift", "Easy Star"), ignore = "Swift"))
        assertNull(ModelNameRules.check("SWIFT", listOf("Swift"), ignore = "Swift"))
        assertEquals(NameProblem.EXISTS, ModelNameRules.check("Easy Star", listOf("Swift", "Easy Star"), ignore = "Swift"))
    }

    @Test
    fun `unique appends a counter and does not pile up suffixes`() {
        assertEquals("Glider", ModelNameRules.unique("Glider", listOf("Swift")))
        assertEquals("Glider (2)", ModelNameRules.unique("Glider", listOf("glider")))
        assertEquals("Glider (3)", ModelNameRules.unique("Glider", listOf("Glider", "Glider (2)")))
        assertEquals("Glider (3)", ModelNameRules.unique("Glider (2)", listOf("Glider", "Glider (2)")))
    }

    @Test
    fun `unique stays within the length limit`() {
        val long = "x".repeat(ModelNameRules.MAX_LENGTH)
        val result = ModelNameRules.unique(long, listOf(long))
        assertTrue(result.length <= ModelNameRules.MAX_LENGTH)
        assertTrue(result.endsWith(" (2)"))
        assertNull(ModelNameRules.check(result, listOf(long)))
    }

    // -- last used --

    @Test
    fun `last used is classified relative to now`() {
        val now = ms(19, 14, 5)
        assertEquals(LastUsed.Never, lastUsedOf(0, now, zone))
        assertEquals(LastUsed.Today(LocalTime.of(9, 40)), lastUsedOf(ms(19, 9, 40), now, zone))
        assertEquals(LastUsed.Yesterday, lastUsedOf(ms(18, 23, 59), now, zone))
        assertEquals(LastUsed.On(java.time.LocalDate.of(2026, 11, 2)), lastUsedOf(ms(2, 10), now, zone))
    }

    @Test
    fun `midnight is the day boundary`() {
        val now = ms(19, 0, 1)
        assertTrue(lastUsedOf(ms(19, 0, 0), now, zone) is LastUsed.Today)
        assertEquals(LastUsed.Yesterday, lastUsedOf(ms(18, 23, 59), now, zone))
    }

    // -- sort --

    @Test
    fun `models sort by name ignoring case or by last use`() {
        val list = listOf(
            ModelEntry(id = "1", name = "zagi", lastUsedMs = 5),
            ModelEntry(id = "2", name = "Alpha", lastUsedMs = 0),
            ModelEntry(id = "3", name = "beta", lastUsedMs = 9),
            ModelEntry(id = "4", name = "Gamma", lastUsedMs = 0),
        )
        assertEquals(listOf("Alpha", "beta", "Gamma", "zagi"), sortModels(list, ModelSort.NAME).map { it.name })
        assertEquals(listOf("beta", "zagi", "Alpha", "Gamma"), sortModels(list, ModelSort.LAST_USED).map { it.name })
    }

    @Test
    fun `sizes are formatted compactly`() {
        assertEquals("512 B", formatSize(512))
        assertEquals("18 KB", formatSize(18_400))
        assertEquals("1.5 MB", formatSize(1_572_864))
    }
}
