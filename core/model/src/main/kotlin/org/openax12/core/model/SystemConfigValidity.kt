package org.openax12.core.model

/**
 * Plausibility rules for the radio-wide settings of the SDK system configuration.
 *
 * `rcGetRcSysConfig()` is unreliable on the AX12 (assumption r5): its failure path returns an
 * uninitialised struct. Every value that comes from the SDK, and every value that is about to be
 * written back, is therefore checked against the code sets seen in real model files before it is
 * believed. The functions return `null` for "plausible" or an English description of the first
 * problem; they never throw.
 */
object SystemConfigValidity {

    /** `fsMode` codes of [FailsafeMode]. */
    val FAILSAFE_MODE_CODES: IntRange = 0..3

    /**
     * `rfModuleMode` codes: 0 internal, 1 external, 2 off and `0xFF` (kept verbatim by the
     * enum mapping, see [RfModuleMode.fromSdk]).
     */
    val RF_MODULE_CODES: Set<Int> = setOf(0, 1, 2, 0xFF)

    /**
     * `stickMode` codes as stored in the model files: 0 = Mode 1, 1 = Mode 2 (observed on the AX12,
     * 2026-11-25; this is independent of the [StickMode] enum, whose codes are corrected separately).
     */
    val STICK_MODE_CODES: IntRange = 0..1

    /** `alramVtg` is a `uint8_t` in 0.1 V. */
    val ALARM_RANGE: IntRange = 0..255

    /**
     * `0xAA`: what the SDK leaves in `crsfBaudRate` of every model file. It is the fill pattern of
     * uninitialised memory (the same model files carry `0xAA` / `0xAAAAAAAA` in unused timer
     * fields), i.e. the SDK never sets the field. It means "not set" and is kept untouched.
     */
    const val BAUD_UNINITIALISED: Int = 0xAA

    /** `crsfBaudRate` codes: 1 = 400k, 2 = 921600, 3 = 1870000 (header) and [BAUD_UNINITIALISED]. */
    val CRSF_BAUD_CODES: Set<Int> = setOf(1, 2, 3, BAUD_UNINITIALISED)

    /** True if [code] is a plausible raw `stickMode`. */
    fun isStickModeCode(code: Int): Boolean = code in STICK_MODE_CODES

    /** True if [code] is a plausible raw `rfModuleMode`. */
    fun isRfModuleCode(code: Int): Boolean = code in RF_MODULE_CODES

    /** True if [deciVolt] fits `alramVtg`. */
    fun isAlarmVoltage(deciVolt: Int): Boolean = deciVolt in ALARM_RANGE

    /** Problem with the raw system fields read from a model file or from the SDK, or null. */
    fun problem(fields: RawSystemFields): String? = when {
        fields.failsafeMode !in FAILSAFE_MODE_CODES -> "fsMode ${fields.failsafeMode} is not 0..3"
        fields.failsafeValues.size != FAILSAFE_CHANNEL_COUNT -> "fsChVal has ${fields.failsafeValues.size} entries"
        fields.failsafeValues.any { it !in INT8 } -> "fsChVal has a value outside the int8 range"
        !isStickModeCode(fields.stickMode) -> "stickMode ${fields.stickMode} is not 0 or 1"
        fields.preFlightCheck.size != PREFLIGHT_SOURCE_COUNT -> "preFlightCheckChannel has ${fields.preFlightCheck.size} entries"
        fields.preFlightCheck.any { it != PREFLIGHT_NO_CHECK && it !in PERCENT_MIN..PERCENT_MAX } ->
            "preFlightCheckChannel has a value that is neither a percentage nor 'no check'"
        !isRfModuleCode(fields.rfModuleMode) -> "rfModuleMode ${fields.rfModuleMode} is not 0, 1, 2 or 255"
        !isAlarmVoltage(fields.alarmVoltageDeciVolt) -> "alramVtg ${fields.alarmVoltageDeciVolt} is not 0..255"
        fields.stickBeep !in UINT8 -> "joytickBeep ${fields.stickBeep} is not 0..255"
        fields.modelMatchId !in UINT8 -> "modelMatchId ${fields.modelMatchId} is not 0..255"
        fields.crsfBaudRate !in CRSF_BAUD_CODES -> "crsfBaudRate ${fields.crsfBaudRate} is not a known code"
        else -> null
    }

    /**
     * Problem with a [SystemConfig] the app is about to read-modify-write, or null. Works on the
     * mapped values: stick mode and RF mode are enums and always valid; the numbers are checked.
     * A baud code of 0 is accepted because the simulator reports it (the SDK path is stricter,
     * see [problem] for [RawSystemFields]).
     */
    fun problem(config: SystemConfig): String? = when {
        !isAlarmVoltage(config.alarmVoltageDeciVolt) -> "the alarm voltage ${config.alarmVoltageDeciVolt} is not 0..255"
        config.failsafeValues.size != FAILSAFE_CHANNEL_COUNT -> "failsafe values: ${config.failsafeValues.size} entries"
        config.failsafeValues.any { it !in INT8 } -> "a failsafe value is outside the int8 range"
        config.preFlightCheck.size != PREFLIGHT_SOURCE_COUNT -> "pre-flight presets: ${config.preFlightCheck.size} entries"
        config.preFlightCheck.any { it != PREFLIGHT_NO_CHECK && it !in PERCENT_MIN..PERCENT_MAX } ->
            "a pre-flight preset is neither a percentage nor 'no check'"
        config.stickBeep !in UINT8 -> "the stick beep ${config.stickBeep} is not 0..255"
        config.modelMatchId !in UINT8 -> "the model match id ${config.modelMatchId} is not 0..255"
        config.crsfBaudRate != 0 && config.crsfBaudRate !in CRSF_BAUD_CODES -> "the CRSF baud code ${config.crsfBaudRate} is unknown"
        else -> null
    }

    private val INT8 = -128..127
    private val UINT8 = 0..255
}
