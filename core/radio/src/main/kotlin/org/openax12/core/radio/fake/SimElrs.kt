package org.openax12.core.radio.fake

import org.openax12.core.model.ELRS_TX_ADDRESS
import org.openax12.core.model.ElrsAck
import org.openax12.core.model.ElrsItemType
import org.openax12.core.model.ElrsMenuItem

/**
 * The parameter menus of the simulated ExpressLRS devices: the TX module (address `0xEE`) with
 * packet rate, telemetry ratio, switch mode, model match, a TX power folder, a VTX folder, a WiFi
 * folder and the bind command, and a small receiver menu (address `0xEC`, only reachable while
 * the link is up). Item indices start at 1 because parent index 0 is the root.
 *
 * Not thread-safe (confined to the simulator worker).
 */
internal class SimElrs(savedValues: Map<Int, Int> = emptyMap()) {

    /** What a menu command leads to. */
    class CommandResult(
        /** The item with its new value, to be re-delivered; null if the value did not change. */
        val item: ElrsMenuItem?,
        /** Acknowledgements in order, each with the delay in ms after the previous one. */
        val acks: List<Pair<Long, ElrsAck>>,
    )

    private val txItems: MutableList<ElrsMenuItem> = txMenu().map { item ->
        val saved = savedValues[item.index]
        if (saved != null && isValueValid(item, saved)) item.copy(value = saved) else item
    }.toMutableList()
    private val rxItems: MutableList<ElrsMenuItem> = rxMenu().toMutableList()

    /** Address of the device whose menu was requested last. */
    private var activeAddress = ELRS_TX_ADDRESS

    /** Indices delivered since the last menu request: only those accept commands. */
    private val delivered = HashSet<Int>()

    /** Command items that are currently executing (WiFi stays on until cancelled). */
    private val executing = HashSet<Int>()

    /** Values of the TX module parameters, for persisting. */
    fun values(): Map<Int, Int> = txItems
        .filter { it.type != ElrsItemType.FOLDER && it.type != ElrsItemType.INFO && it.type != ElrsItemType.COMMAND }
        .associate { it.index to it.value }

    /** Packet rate option index: reported as the RF mode of the link statistics. */
    val rfMode: Int get() = txItems.first { it.index == ITEM_PACKET_RATE }.value

    /** Packet rate in Hz. */
    val packetRateHz: Int get() = PACKET_RATES_HZ.getOrElse(rfMode) { 250 }

    /** Configured maximum TX power in mW. */
    val txPowerMw: Int get() = POWER_MW.getOrElse(txItems.first { it.index == ITEM_MAX_POWER }.value) { 100 }

    /** Device name and items of the menu of [address], or null if no such device answers. */
    fun menu(address: Int, linkUp: Boolean): Pair<String, List<ElrsMenuItem>>? = when (address) {
        ELRS_TX_ADDRESS -> TX_DEVICE_NAME to txItems.toList()
        CrsfEncoder.ADDRESS_RECEIVER -> if (linkUp) RX_DEVICE_NAME to rxItems.toList() else null
        else -> null
    }

    /** Starts a new menu request: commands are refused until items were delivered again. */
    fun beginRequest(address: Int) {
        activeAddress = address
        delivered.clear()
    }

    /** Marks an item as delivered to the host. */
    fun markDelivered(index: Int) {
        delivered += index
    }

    /** True if a command for item [index] may be forwarded (it was delivered since the last request). */
    fun isDelivered(index: Int): Boolean = index in delivered

    /**
     * Applies `rcElrsSendMenuCmd(index, value)` to the menu requested last.
     *
     * - Selections and numbers take [value] if it is valid for the item.
     * - Commands follow the CRSF command steps: [STEP_START] starts, [STEP_CANCEL] cancels,
     *   [STEP_POLL] asks for the state. Binding takes two seconds; WiFi stays on until cancelled.
     *
     * @return null if the item does not exist, is a folder / info item or [value] is not valid for it
     */
    fun command(index: Int, value: Int): CommandResult? {
        val items = if (activeAddress == CrsfEncoder.ADDRESS_RECEIVER) rxItems else txItems
        val position = items.indexOfFirst { it.index == index }
        if (position < 0) return null
        val item = items[position]
        if (!item.enabled) return null
        return when (item.type) {
            ElrsItemType.FOLDER, ElrsItemType.INFO, ElrsItemType.STRING -> null
            ElrsItemType.COMMAND -> runCommand(item, value)
            else -> {
                if (!isValueValid(item, value)) return null
                val updated = item.copy(value = value)
                items[position] = updated
                CommandResult(updated, listOf(ACK_DELAY_MS to ElrsAck(item.type, STATUS_READY, index, "")))
            }
        }
    }

    private fun runCommand(item: ElrsMenuItem, step: Int): CommandResult? {
        val index = item.index
        fun ack(status: Int, text: String) = ElrsAck(ElrsItemType.COMMAND, status, index, text)
        return when (step) {
            STEP_START -> when (index) {
                ITEM_BIND -> CommandResult(
                    null,
                    listOf(ACK_DELAY_MS to ack(STATUS_PROGRESS, "Binding..."), BIND_DURATION_MS to ack(STATUS_READY, "")),
                )
                ITEM_ENABLE_WIFI, ITEM_ENABLE_RX_WIFI, RX_ITEM_ENABLE_WIFI -> {
                    executing += index
                    CommandResult(null, listOf(ACK_DELAY_MS to ack(STATUS_PROGRESS, "WiFi Running...")))
                }
                else -> CommandResult(null, listOf(ACK_DELAY_MS to ack(STATUS_READY, "")))
            }
            STEP_CANCEL -> {
                executing -= index
                CommandResult(null, listOf(ACK_DELAY_MS to ack(STATUS_READY, "")))
            }
            STEP_POLL -> {
                val running = index in executing
                CommandResult(
                    null,
                    listOf(ACK_DELAY_MS to ack(if (running) STATUS_PROGRESS else STATUS_READY, if (running) "WiFi Running..." else "")),
                )
            }
            else -> null
        }
    }

    private fun isValueValid(item: ElrsMenuItem, value: Int): Boolean = when (item.type) {
        ElrsItemType.TEXT_SELECTION -> value in item.options.indices
        ElrsItemType.UINT8 -> value in 0..255
        ElrsItemType.INT8 -> value in -128..127
        ElrsItemType.UINT16 -> value in 0..65535
        ElrsItemType.INT16 -> value in -32768..32767
        else -> false
    }

    companion object {
        const val TX_DEVICE_NAME = "AX12 Internal ELRS"
        const val RX_DEVICE_NAME = "ELRS RX"

        /** CRSF command steps (the value of a command item). */
        const val STEP_START = 1
        const val STEP_CANCEL = 5
        const val STEP_POLL = 6

        /** CRSF command states (the status of a command acknowledgement). */
        const val STATUS_READY = 0
        const val STATUS_PROGRESS = 2

        /** Time the module takes to acknowledge a command. */
        const val ACK_DELAY_MS = 120L

        /** Time the module stays in bind mode. */
        const val BIND_DURATION_MS = 2000L

        /** Pause between two menu items while the menu is read from the module. */
        const val ITEM_DELAY_MS = 30L

        const val ITEM_PACKET_RATE = 1
        const val ITEM_TELEMETRY_RATIO = 2
        const val ITEM_MAX_POWER = 6
        const val ITEM_ENABLE_WIFI = 14
        const val ITEM_ENABLE_RX_WIFI = 15
        const val ITEM_BIND = 16
        const val RX_ITEM_ENABLE_WIFI = 3

        private val PACKET_RATES_HZ = listOf(50, 100, 150, 250, 333, 500)
        private val POWER_MW = listOf(10, 25, 50, 100, 250, 500, 1000)

        private fun selection(index: Int, parent: Int, name: String, value: Int, options: List<String>, units: String = "") =
            ElrsMenuItem(index, parent, value, ElrsItemType.TEXT_SELECTION, name = name, options = options, deviceName = TX_DEVICE_NAME, units = units)

        private fun folder(index: Int, parent: Int, name: String) =
            ElrsMenuItem(index, parent, 0, ElrsItemType.FOLDER, name = name, deviceName = TX_DEVICE_NAME)

        private fun command(index: Int, parent: Int, name: String) =
            ElrsMenuItem(index, parent, 0, ElrsItemType.COMMAND, name = name, deviceName = TX_DEVICE_NAME)

        private fun info(index: Int, parent: Int, name: String, text: String) =
            ElrsMenuItem(index, parent, 0, ElrsItemType.INFO, name = name, options = listOf(text), deviceName = TX_DEVICE_NAME)

        /** The TX module menu with its factory values. */
        fun txMenu(): List<ElrsMenuItem> = listOf(
            selection(ITEM_PACKET_RATE, 0, "Packet Rate", 3, listOf("50Hz", "100Hz Full", "150Hz", "250Hz", "333Hz Full", "500Hz")),
            selection(ITEM_TELEMETRY_RATIO, 0, "Telem Ratio", 0, listOf("Std", "Off", "1:128", "1:64", "1:32", "1:16", "1:8", "1:4", "1:2", "Race")),
            selection(3, 0, "Switch Mode", 0, listOf("Wide", "Hybrid")),
            selection(4, 0, "Model Match", 0, listOf("Off", "On")),
            folder(5, 0, "TX Power"),
            selection(ITEM_MAX_POWER, 5, "Max Power", 3, listOf("10", "25", "50", "100", "250", "500", "1000"), units = "mW"),
            selection(7, 5, "Dynamic", 0, listOf("Off", "Dyn", "AUX9", "AUX10", "AUX11", "AUX12")),
            selection(8, 5, "Fan Thresh", 4, listOf("10mW", "25mW", "50mW", "100mW", "250mW", "500mW", "1000mW")),
            folder(9, 0, "VTX Administrator"),
            selection(10, 9, "Band", 0, listOf("Off", "A", "B", "E", "F", "R", "L")),
            ElrsMenuItem(11, 9, 1, ElrsItemType.UINT8, name = "Channel", deviceName = TX_DEVICE_NAME),
            command(12, 9, "Send VTx"),
            folder(13, 0, "WiFi Connectivity"),
            command(ITEM_ENABLE_WIFI, 13, "Enable WiFi"),
            command(ITEM_ENABLE_RX_WIFI, 13, "Enable Rx WiFi"),
            command(ITEM_BIND, 0, "Bind"),
            info(17, 0, "Bad/Good", "0/250"),
            info(18, 0, "3.5.3 ISM2G4", "simulator"),
        )

        /** The receiver menu. */
        fun rxMenu(): List<ElrsMenuItem> = listOf(
            ElrsMenuItem(1, 0, 0, ElrsItemType.TEXT_SELECTION, name = "Protocol", options = listOf("CRSF", "Inverted CRSF", "SBUS"), deviceName = RX_DEVICE_NAME),
            ElrsMenuItem(2, 0, 3, ElrsItemType.TEXT_SELECTION, name = "Tlm Power", options = listOf("10", "25", "50", "100"), deviceName = RX_DEVICE_NAME, units = "mW"),
            ElrsMenuItem(RX_ITEM_ENABLE_WIFI, 0, 0, ElrsItemType.COMMAND, name = "Enable Rx WiFi", deviceName = RX_DEVICE_NAME),
            ElrsMenuItem(4, 0, 0, ElrsItemType.INFO, name = "Model Id", options = listOf("Off"), deviceName = RX_DEVICE_NAME),
        )
    }
}
