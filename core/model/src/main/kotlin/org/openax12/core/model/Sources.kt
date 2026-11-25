package org.openax12.core.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// Fixed sizes of the AX12 firmware / SDK. Validate every index against these before it reaches
// the SDK (out-of-range indices are undefined behaviour there).
// ---------------------------------------------------------------------------------------------

/** Number of output channels. Assumption 11 in docs/ASSUMPTIONS.md (not proven on the device). */
const val CHANNEL_COUNT: Int = 16

/** Number of curves of a model (indices 0..17). */
const val CURVE_COUNT: Int = 18

/** Capacity of the fixed `xVal`/`yVal` arrays of a curve. */
const val CURVE_MAX_POINTS: Int = 12

/** Curve index meaning "apply no curve". */
const val NO_CURVE: Int = 0xFF

/** Dual-rate slots per output channel (slot indices 0..2). */
const val DR_SLOTS: Int = 3

/** Number of timers (in-group indices 0..2, device timer numbers 0..2). */
const val TIMER_COUNT: Int = 3

/** Number of counters (in-group indices 0..2, device timer numbers 3..5). */
const val COUNTER_COUNT: Int = 3

/** Number of input sources (indices 0..41). */
const val SOURCE_COUNT: Int = 42

/** Length of `preFlightCheckChannel` in the system config: sources Rud..SP (0..35). */
const val PREFLIGHT_SOURCE_COUNT: Int = 36

/** Pre-flight preset value meaning "do not check this source". */
const val PREFLIGHT_NO_CHECK: Int = 127

/** Length of the custom failsafe value array in the system config. */
const val FAILSAFE_CHANNEL_COUNT: Int = 16

/** App-side logical switches L1..L32 (indices 0..31). */
const val LOGICAL_SWITCH_COUNT: Int = 32

/** App-side special functions SF1..SF64 (indices 0..63). */
const val SPECIAL_FUNCTION_COUNT: Int = 64

/** Maximum number of flight modes (one 3-position switch). */
const val FLIGHT_MODE_MAX: Int = 3

/** CRSF address of the transmitter module, the usual argument of the ELRS menu request. */
const val ELRS_TX_ADDRESS: Int = 0xEE

/** Lowest / highest percentage used for sources, weights, limits and channel values. */
const val PERCENT_MIN: Int = -100
const val PERCENT_MAX: Int = 100

/** Category of an input source, derived from its SDK index. */
enum class SourceKind {
    /** Rud, Ele, Thr, Ail (0..3). */
    STICK,

    /** P1..P8 (4..11): self-centring knobs. */
    POT,

    /** S1..S8 (12..19): knobs/sliders that do not self-centre. */
    SLIDER,

    /** SA..SP (20..35). */
    SWITCH,

    /** CNT1..CNT6 (36..41). */
    COUNTER,
}

/**
 * SDK source indices (`SRC_IDX_DEF` in `rmAxSdkTypes.h`) as plain `Int` constants, for `when`
 * branches and array indexing. Use [SourceId] in APIs.
 */
object Sources {
    const val RUD = 0
    const val ELE = 1
    const val THR = 2
    const val AIL = 3
    const val P1 = 4
    const val P2 = 5
    const val P3 = 6
    const val P4 = 7
    const val P5 = 8
    const val P6 = 9
    const val P7 = 10
    const val P8 = 11
    const val S1 = 12
    const val S2 = 13
    const val S3 = 14
    const val S4 = 15
    const val S5 = 16
    const val S6 = 17
    const val S7 = 18
    const val S8 = 19
    const val SA = 20
    const val SB = 21
    const val SC = 22
    const val SD = 23
    const val SE = 24
    const val SF = 25
    const val SG = 26
    const val SH = 27
    const val SI = 28
    const val SJ = 29
    const val SK = 30
    const val SL = 31
    const val SM = 32
    const val SN = 33
    const val SO = 34
    const val SP = 35
    const val CNT1 = 36
    const val CNT2 = 37
    const val CNT3 = 38
    const val CNT4 = 39
    const val CNT5 = 40
    const val CNT6 = 41

    val STICKS: IntRange = RUD..AIL
    val POTS: IntRange = P1..P8
    val SLIDERS: IntRange = S1..S8
    val SWITCHES: IntRange = SA..SP
    val COUNTERS: IntRange = CNT1..CNT6
    val ALL: IntRange = 0 until SOURCE_COUNT

    private val NAMES: List<String> = buildList {
        add("Rud"); add("Ele"); add("Thr"); add("Ail")
        for (i in 1..8) add("P$i")
        for (i in 1..8) add("S$i")
        for (c in 'A'..'P') add("S$c")
        for (i in 1..6) add("CNT$i")
    }

    /** Default (firmware) name of a source index, e.g. `Thr`, `P3`, `SA`, `CNT2`; `"?"` if invalid. */
    fun defaultName(index: Int): String = NAMES.getOrElse(index) { "?" }

    /** Source index for a default name (case-insensitive), or -1. */
    fun indexOfName(name: String): Int = NAMES.indexOfFirst { it.equals(name, ignoreCase = true) }

    /** Category of a source index. Invalid indices report [SourceKind.COUNTER]; check [isValid] first. */
    fun kindOf(index: Int): SourceKind = when (index) {
        in STICKS -> SourceKind.STICK
        in POTS -> SourceKind.POT
        in SLIDERS -> SourceKind.SLIDER
        in SWITCHES -> SourceKind.SWITCH
        else -> SourceKind.COUNTER
    }

    /** True if [index] is one of the [SOURCE_COUNT] sources. */
    fun isValid(index: Int): Boolean = index in ALL
}

/**
 * Type-safe SDK input-source index (0 Rud, 1 Ele, 2 Thr, 3 Ail, 4–11 P1–P8, 12–19 S1–S8,
 * 20–35 SA–SP, 36–41 CNT1–CNT6). Serialized as a plain integer.
 */
@Serializable
@JvmInline
value class SourceId(val index: Int) : Comparable<SourceId> {

    /** True if [index] is one of the [SOURCE_COUNT] sources. */
    val isValid: Boolean get() = Sources.isValid(index)

    /** Stick, pot, slider, switch or counter. */
    val kind: SourceKind get() = Sources.kindOf(index)

    /** Firmware default name (`Thr`, `SA`, …). The user-visible name comes from [SourceInfo.name]. */
    val defaultName: String get() = Sources.defaultName(index)

    /** True for Rud / Ele / Thr / Ail. */
    val isStick: Boolean get() = index in Sources.STICKS
    /** True for P1–P8. */
    val isPot: Boolean get() = index in Sources.POTS
    /** True for S1–S8. */
    val isSlider: Boolean get() = index in Sources.SLIDERS
    /** True for SA–SP. */
    val isSwitch: Boolean get() = index in Sources.SWITCHES
    /** True for CNT1–CNT6. */
    val isCounter: Boolean get() = index in Sources.COUNTERS

    /** True for sources that have a pre-flight-check preset (Rud..SP). */
    val hasPreFlightCheck: Boolean get() = index in 0 until PREFLIGHT_SOURCE_COUNT

    override fun compareTo(other: SourceId): Int = index.compareTo(other.index)

    override fun toString(): String = defaultName

    companion object {
        val RUD = SourceId(Sources.RUD)
        val ELE = SourceId(Sources.ELE)
        val THR = SourceId(Sources.THR)
        val AIL = SourceId(Sources.AIL)
        val SA = SourceId(Sources.SA)
        val SB = SourceId(Sources.SB)
        val SC = SourceId(Sources.SC)
        val SD = SourceId(Sources.SD)
        val SE = SourceId(Sources.SE)
        val SF = SourceId(Sources.SF)
        val SG = SourceId(Sources.SG)
        val SH = SourceId(Sources.SH)

        /** All 42 sources in SDK order. */
        val all: List<SourceId> = Sources.ALL.map(::SourceId)

        /** P1..P8, [number] is 1-based. */
        fun pot(number: Int): SourceId = SourceId(Sources.P1 + number - 1)

        /** S1..S8, [number] is 1-based. */
        fun slider(number: Int): SourceId = SourceId(Sources.S1 + number - 1)

        /** SA..SP by letter (`'A'..'P'`, case-insensitive). */
        fun switch(letter: Char): SourceId = SourceId(Sources.SA + (letter.uppercaseChar() - 'A'))

        /** CNT1..CNT6, [number] is 1-based. */
        fun counter(number: Int): SourceId = SourceId(Sources.CNT1 + number - 1)

        /** Looks a source up by its default name (`"Thr"`, `"sa"`, `"P3"`); null if unknown. */
        fun fromName(name: String): SourceId? =
            Sources.indexOfName(name).takeIf { it >= 0 }?.let(::SourceId)
    }
}

/**
 * Position of a 2- or 3-position switch. Assumption 3: the firmware reports the position as a
 * logic value of −100 (up) / 0 (mid) / +100 (down).
 */
@Serializable
enum class SwitchPosition(val logic: Int) {
    UP(-100),
    MID(0),
    DOWN(100);

    companion object {
        /** Maps a logic value to the nearest position (thresholds at ±50). */
        fun fromLogic(logic: Int): SwitchPosition = when {
            logic < -50 -> UP
            logic > 50 -> DOWN
            else -> MID
        }
    }
}
