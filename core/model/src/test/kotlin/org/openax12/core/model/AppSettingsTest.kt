package org.openax12.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `closing the stock app is on by default`() {
        assertTrue(AppSettings().closeStockApp)
    }

    @Test
    fun `settings stored by an older version decode with the stock app closing on`() {
        val decoded = Ax12Json.decodeFromString(AppSettings.serializer(), """{"schemaVersion":1,"theme":"DARK"}""")
        assertEquals(AppTheme.DARK, decoded.theme)
        assertTrue(decoded.closeStockApp)
    }

    @Test
    fun `the closing choice survives a round trip`() {
        val original = AppSettings(closeStockApp = false)
        val decoded = Ax12Json.decodeFromString(AppSettings.serializer(), Ax12Json.encodeToString(AppSettings.serializer(), original))
        assertFalse(decoded.closeStockApp)
        assertEquals(original, decoded)
    }
}
