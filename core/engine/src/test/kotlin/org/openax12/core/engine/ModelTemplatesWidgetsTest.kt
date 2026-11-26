package org.openax12.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.WorkingConfig

class ModelTemplatesWidgetsTest {

    @Test
    fun `every template pins starter widgets so that a new model opens on them`() {
        for (template in ModelTemplates.all) {
            val extras = ModelTemplates.build(template, TemplateOptions(), WorkingConfig.EMPTY).extras
            assertTrue("${template.id} pins no widget", extras.telemetry.pinnedWidgets.isNotEmpty())
            // No stored choice: the pinned widgets are shown by default.
            assertEquals(template.id, HomeCenterCard.WIDGETS, extras.home.resolvedCenterCard(hasPinnedWidgets = true))
        }
    }
}
