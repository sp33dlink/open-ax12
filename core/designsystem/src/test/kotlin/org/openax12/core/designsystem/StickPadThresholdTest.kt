package org.openax12.core.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickPadThresholdTest {
    @Test
    fun almostFullDeflectionDoesNotLight() {
        for (v in listOf(98f, 99f, 99.6f, 99.99f)) {
            assertFalse("+$v", isStickAtPositiveMax(v))
            assertFalse("-$v", isStickAtNegativeMax(-v))
        }
    }

    @Test
    fun fullDeflectionLights() {
        assertTrue(isStickAtPositiveMax(100f))
        assertTrue(isStickAtNegativeMax(-100f))
    }

    @Test
    fun oppositeSideNeverLights() {
        assertFalse(isStickAtNegativeMax(100f))
        assertFalse(isStickAtPositiveMax(-100f))
        assertFalse(isStickAtPositiveMax(0f))
        assertFalse(isStickAtNegativeMax(0f))
    }
}
