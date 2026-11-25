package org.openax12.feature.system.elrs

import org.openax12.core.model.ELRS_TX_ADDRESS
import org.openax12.core.model.ElrsAck
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.ElrsMenuItem
import org.openax12.core.model.ElrsStatus
import org.openax12.core.radio.ConnectionState

/** The CRSF devices whose parameter menu can be browsed. */
internal enum class ElrsDevice(val address: Int) {
    /** The transmitter's ELRS module (`0xEE`). */
    TX_MODULE(ELRS_TX_ADDRESS),

    /** The receiver (`0xEC`); only answers while the link is up. */
    RECEIVER(0xEC),
}

/** Values of a command item (CRSF parameter command steps). */
internal object ElrsCommandStep {
    const val START = 1
    const val CONFIRM = 4
    const val CANCEL = 5
    const val POLL = 6
}

/** Progress of a command item, derived from the last acknowledgement. */
internal sealed interface CommandProgress {
    /** The command runs; [text] is the module's status text. */
    data class Running(val text: String) : CommandProgress

    /** The module asks for confirmation. */
    data class NeedsConfirmation(val text: String) : CommandProgress

    /** Nothing running (finished or cancelled). */
    data object Idle : CommandProgress
}

/** CRSF command status codes inside an acknowledgement. */
private const val STATUS_PROGRESS = 2
private const val STATUS_CONFIRMATION_NEEDED = 3

/** Maps the status of a command acknowledgement to [CommandProgress]. */
internal fun commandProgress(ack: ElrsAck): CommandProgress = when (ack.status) {
    STATUS_PROGRESS -> CommandProgress.Running(ack.options)
    STATUS_CONFIRMATION_NEEDED -> CommandProgress.NeedsConfirmation(ack.options)
    else -> CommandProgress.Idle
}

/** Pure helpers on the flat item list the SDK delivers (`index`, `parentIndex`). */
internal object ElrsTree {

    /** Index of the root pseudo-folder: `parentIndex == 0`. */
    const val ROOT = 0

    /** Visible items inside folder [parent], in menu order. */
    fun children(items: List<ElrsMenuItem>, parent: Int): List<ElrsMenuItem> =
        items.filter { it.parentIndex == parent && it.visible && it.index != parent }.sortedBy { it.index }

    /** The folder chain from the root down to [folder] (exclusive of the root); cycle safe. */
    fun path(items: List<ElrsMenuItem>, folder: Int): List<ElrsMenuItem> {
        val result = ArrayList<ElrsMenuItem>()
        var current = folder
        val seen = HashSet<Int>()
        while (current != ROOT && seen.add(current)) {
            val item = items.firstOrNull { it.index == current } ?: break
            result.add(0, item)
            current = item.parentIndex
        }
        return result
    }

    /** The parent folder index of [folder] (root when unknown). */
    fun parentOf(items: List<ElrsMenuItem>, folder: Int): Int =
        items.firstOrNull { it.index == folder }?.parentIndex ?: ROOT

    /** Replaces the item with the same index or appends it. */
    fun upsert(items: List<ElrsMenuItem>, item: ElrsMenuItem): List<ElrsMenuItem> {
        val position = items.indexOfFirst { it.index == item.index }
        return if (position < 0) items + item else items.toMutableList().also { it[position] = item }
    }

    /** Value range a numeric item type accepts, or null for non-numeric types. */
    fun numericRange(type: Int): IntRange? = when (type) {
        ElrsItemType.UINT8 -> 0..255
        ElrsItemType.INT8 -> -128..127
        ElrsItemType.UINT16 -> 0..65_535
        ElrsItemType.INT16 -> -32_768..32_767
        else -> null
    }

    /** True for items the user can change by a value (selection or integer). */
    fun isEditable(item: ElrsMenuItem): Boolean =
        item.type == ElrsItemType.TEXT_SELECTION && item.options.isNotEmpty() || numericRange(item.type) != null

    /** Name of an item type for diagnostics (`TEXT_SELECTION`, `FOLDER`, … or the code). */
    fun typeName(type: Int): String = when (type) {
        ElrsItemType.UINT8 -> "UINT8"
        ElrsItemType.INT8 -> "INT8"
        ElrsItemType.UINT16 -> "UINT16"
        ElrsItemType.INT16 -> "INT16"
        ElrsItemType.FLOAT -> "FLOAT"
        ElrsItemType.TEXT_SELECTION -> "TEXT_SELECTION"
        ElrsItemType.STRING -> "STRING"
        ElrsItemType.FOLDER -> "FOLDER"
        ElrsItemType.INFO -> "INFO"
        ElrsItemType.COMMAND -> "COMMAND"
        else -> "type $type"
    }
}

/** Everything the ELRS screen shows. */
internal data class ElrsUiState(
    val device: ElrsDevice = ElrsDevice.TX_MODULE,
    val deviceName: String = "",
    val items: List<ElrsMenuItem> = emptyList(),
    val folder: Int = ElrsTree.ROOT,
    val loading: Boolean = false,
    /** True when a menu request produced no item at all. */
    val noAnswer: Boolean = false,
    val status: ElrsStatus? = null,
    val statusPending: Boolean = false,
    val commands: Map<Int, CommandProgress> = emptyMap(),
    val connection: ConnectionState = ConnectionState.Stopped,
    val linkUp: Boolean = false,
) {
    /** The items shown in the current folder. */
    val visibleItems: List<ElrsMenuItem> get() = ElrsTree.children(items, folder)

    /** The folder chain of the current folder. */
    val breadcrumb: List<ElrsMenuItem> get() = ElrsTree.path(items, folder)

    /** The radio can talk to the module. */
    val radioReady: Boolean get() = connection.isReady
}
