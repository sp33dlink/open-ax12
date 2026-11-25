package org.openax12.core.data.library

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ModelNamesTest {

    @Test
    fun validateNormalisesWhitespaceAndRejectsBadNames() {
        assertEquals("Swift 2", ModelNames.validate("  Swift   2 "))
        assertNull(ModelNames.validate(""))
        assertNull(ModelNames.validate("a/b"))
        assertNull(ModelNames.validate(".hidden"))
        assertNull(ModelNames.validate("CON"))
        assertNull(ModelNames.validate("x".repeat(ModelNames.MAX_LENGTH + 1)))
    }

    @Test
    fun sanitizeAlwaysReturnsValidName() {
        assertEquals("a_b", ModelNames.sanitize("a/b"))
        assertEquals(ModelNames.FALLBACK, ModelNames.sanitize("???"))
        assertTrue(ModelNames.validate(ModelNames.sanitize("x".repeat(100) + ":")) != null)
    }

    @Test
    fun uniqueAddsCounterWithoutPilingUpSuffixes() {
        assertEquals("Swift", ModelNames.unique("Swift", listOf("Other")))
        assertEquals("Swift (2)", ModelNames.unique("Swift", listOf("swift")))
        assertEquals("Swift (3)", ModelNames.unique("Swift (2)", listOf("Swift", "Swift (2)")))
    }
}
