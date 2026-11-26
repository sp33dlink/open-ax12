package org.openax12.core.model

import kotlinx.serialization.Serializable

// Typed views of SDK integer codes.
//
// LOSSY ON PURPOSE, LOSSLESS IN THE BRIDGE: every `fromSdk` maps codes it does not know to a
// default, so the Kotlin value alone cannot reproduce an unknown device code. The round trip
// read -> copy(other field) -> write stays lossless because of the write rule every `Radio`
// implementation follows (see "Read-modify-write" in the `Radio` KDoc): a setter overlays the
// model object onto a FRESH native read and writes an enum- or Boolean-mapped field only when
// the Kotlin value differs from the mapped value of that fresh read, e.g.
//
//     if (cfg.stickMode != StickMode.fromSdk(native.stickMode)) native.stickMode = cfg.stickMode.sdk
//
// Otherwise the raw native integer stays untouched. Consequences for callers: an unknown device
// code survives until the user really changes that field, and a UI may show the mapped default
// for a code it cannot name.

/**
 * Comparison used by every SDK switch condition (`swCmpType`, `rstSwCmpType`, `cmpType`,
 * `triggerType`). [sdk] is the integer the SDK expects.
 */
@Serializable
enum class CompareOp(val sdk: Int) {
    /** No condition: the owning item is not gated by a switch. */
    DISABLED(0),
    GREATER(1),
    EQUAL(2),
    LESS(3);

    companion object {
        /** Unknown codes map to [DISABLED]. */
        fun fromSdk(value: Int): CompareOp = entries.firstOrNull { it.sdk == value } ?: DISABLED
    }
}

/** How a mix line is combined with the lines before it (`MIXED_MODE`). */
@Serializable
enum class MixMode(val sdk: Int) {
    /** `:=` */
    REPLACE(0),

    /** `+=` */
    ADD(1),

    /** `*=` */
    MULTIPLY(2);

    companion object {
        /** Unknown codes map to [ADD]. */
        fun fromSdk(value: Int): MixMode = entries.firstOrNull { it.sdk == value } ?: ADD
    }
}

/** Curve type (`IN_CURVE_TYPE`). */
@Serializable
enum class CurveType(val sdk: Int) {
    NONE(0),

    /** Differential: separate ratio for the positive and the negative half. */
    DIFF(1),
    EXPO(2),

    /** Built-in function. */
    FUNC(3),

    /** Custom curve with up to [CURVE_MAX_POINTS] points. */
    CUSTOM(4);

    companion object {
        /** Unknown codes map to [NONE]. */
        fun fromSdk(value: Int): CurveType = entries.firstOrNull { it.sdk == value } ?: NONE
    }
}

/**
 * RF module selection of the system config. The SDK also reports `0xFF` for "off"; a device value
 * of `0xFF` is kept as it is unless the mode is really changed (see the note at the top of this file).
 */
@Serializable
enum class RfModuleMode(val sdk: Int) {
    INTERNAL(0),
    EXTERNAL(1),
    OFF(2);

    companion object {
        /** `2` and `0xFF` (and anything unknown) map to [OFF]. */
        fun fromSdk(value: Int): RfModuleMode = when (value) {
            0 -> INTERNAL
            1 -> EXTERNAL
            else -> OFF
        }
    }
}

/**
 * Stick mode of the transmitter. The SDK documents only the codes 1 and 2; any other device value
 * is shown as [MODE_2] and kept as it is unless the mode is really changed (see the note at the top
 * of this file).
 *
 * The codes are 0-based (verified 2026-11-25 in the stock app, which shows `stickMode + 1` and
 * writes `mode - 1`): `0` = Mode 1, `1` = Mode 2. The SDK header's "1 or 2" is wrong.
 */
@Serializable
enum class StickMode(val sdk: Int) {
    /** Left: Ele (vertical) / Rud (horizontal). Right: Thr / Ail. */
    MODE_1(0),

    /** Left: Thr (vertical) / Rud (horizontal). Right: Ele / Ail. */
    MODE_2(1);

    /** Source on the vertical axis of the left stick. */
    val leftVertical: SourceId get() = if (this == MODE_1) SourceId.ELE else SourceId.THR

    /** Source on the horizontal axis of the left stick. */
    val leftHorizontal: SourceId get() = SourceId.RUD

    /** Source on the vertical axis of the right stick. */
    val rightVertical: SourceId get() = if (this == MODE_1) SourceId.THR else SourceId.ELE

    /** Source on the horizontal axis of the right stick. */
    val rightHorizontal: SourceId get() = SourceId.AIL

    companion object {
        /** Unknown codes map to [MODE_2]. */
        fun fromSdk(value: Int): StickMode = if (value == 0) MODE_1 else MODE_2
    }
}

/** Firmware alert while a timer counts (`RCTIME_COUNTDOWN`). App-side voice options are in [TimerVoiceOptions]. */
@Serializable
enum class TimerAlertMode(val sdk: Int) {
    SILENT(0),
    BEEPS(1),
    VOICE(2),
    HAPTIC(3),
    BEEPS_HAPTIC(4),
    VOICE_HAPTIC(5);

    companion object {
        /** Notify code sent with a timer notification when the timer was reset (`TCD_NOTFY_RESET`). */
        const val NOTIFY_RESET: Int = 0x81

        /** Unknown codes map to [SILENT]. */
        fun fromSdk(value: Int): TimerAlertMode = entries.firstOrNull { it.sdk == value } ?: SILENT
    }
}

/** Failsafe mode of the system config. Not persisted by the current SDK (see CLAUDE.md). */
@Serializable
enum class FailsafeMode(val sdk: Int) {
    NONE(0),

    /** Receiver keeps the last received values. */
    HOLD(1),

    /** Receiver stops sending pulses. */
    NO_PULSES(2),

    /** Receiver outputs the custom values. */
    CUSTOM(3);

    companion object {
        /** Unknown codes map to [NONE]. */
        fun fromSdk(value: Int): FailsafeMode = entries.firstOrNull { it.sdk == value } ?: NONE
    }
}
