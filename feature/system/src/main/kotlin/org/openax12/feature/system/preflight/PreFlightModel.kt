package org.openax12.feature.system.preflight

import org.openax12.core.model.ChecklistItem
import org.openax12.core.model.PREFLIGHT_SOURCE_COUNT
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.SystemConfig
import org.openax12.core.radio.ConnectionState

/**
 * One line of the pre-flight overview.
 *
 * @property preset the configured preset in percent, or null for "do not check"
 * @property failing the firmware reported this source as not at its preset
 */
internal data class PreFlightRow(
    val source: SourceId,
    val name: String,
    val preset: Int?,
    val failing: Boolean,
)

/**
 * The sources worth listing: every source with a preset and every source the firmware reported
 * as failing. [states] is the retained `RuntimeEngine.preFlightCheck` (per source, non-zero =
 * not at its preset), null when no alarm is pending.
 */
internal fun preFlightRows(system: SystemConfig?, sources: List<SourceInfo>, states: List<Int>?): List<PreFlightRow> {
    val rows = ArrayList<PreFlightRow>()
    for (index in 0 until PREFLIGHT_SOURCE_COUNT) {
        val id = SourceId(index)
        val preset = system?.preFlightPreset(id)
        val failing = (states?.getOrNull(index) ?: 0) != 0
        if (preset == null && !failing) continue
        val name = sources.firstOrNull { it.index == index }?.name?.takeIf { it.isNotBlank() } ?: id.defaultName
        rows += PreFlightRow(id, name, preset, failing)
    }
    return rows
}

/** Pure list edits of the per-model checklist. Out-of-range indices change nothing. */
internal object ChecklistOps {

    /** Appends a line; blank text is ignored. */
    fun add(list: List<ChecklistItem>, text: String): List<ChecklistItem> =
        if (text.isBlank()) list else list + ChecklistItem(text.trim())

    /** Replaces the text of line [index]; blank text removes nothing and is ignored. */
    fun rename(list: List<ChecklistItem>, index: Int, text: String): List<ChecklistItem> =
        if (index !in list.indices || text.isBlank()) list else list.toMutableList().also { it[index] = it[index].copy(text = text.trim()) }

    /** Toggles whether line [index] is read aloud. */
    fun toggleSpoken(list: List<ChecklistItem>, index: Int): List<ChecklistItem> =
        if (index !in list.indices) list else list.toMutableList().also { it[index] = it[index].copy(spoken = !it[index].spoken) }

    /** Removes line [index]. */
    fun remove(list: List<ChecklistItem>, index: Int): List<ChecklistItem> =
        if (index !in list.indices) list else list.toMutableList().also { it.removeAt(index) }

    /** Moves line [index] by [delta] positions (clamped to the list). */
    fun move(list: List<ChecklistItem>, index: Int, delta: Int): List<ChecklistItem> {
        if (index !in list.indices) return list
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return list
        val result = list.toMutableList()
        result.add(target, result.removeAt(index))
        return result
    }

    /** The lines marked "read aloud", in order. */
    fun spokenTexts(list: List<ChecklistItem>): List<String> = list.filter { it.spoken }.map { it.text }
}

/** Everything the pre-flight screen shows. */
internal data class PreFlightUiState(
    val connection: ConnectionState = ConnectionState.Stopped,
    val rows: List<PreFlightRow> = emptyList(),
    /** True while the firmware's pre-flight alarm is pending. */
    val alarmPending: Boolean = false,
    val modelName: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    /** Lines ticked off during this visit (not persisted). */
    val checked: Set<Int> = emptySet(),
) {
    /** Number of sources the firmware reported as failing. */
    val failingCount: Int get() = rows.count { it.failing }

    /** All lines ticked. */
    val allChecked: Boolean get() = checklist.isNotEmpty() && checked.size >= checklist.size
}
