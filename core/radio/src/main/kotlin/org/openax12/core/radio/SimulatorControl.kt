package org.openax12.core.radio

import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.model.SensorId
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchPosition

/** Faults the simulator can produce on request, to exercise error handling in the UI. */
enum class SimulatedFault {
    /** The next `start()` ends in [ConnectionError.SerialPortBusy] (`mayBeStockAppConflict` → "close the stock app" hint). */
    SERIAL_PORT_BUSY,

    /** The next `start()` ends in [ConnectionError.DeviceTimeout]. */
    DEVICE_TIMEOUT,

    /** The next `saveConfigToDevice()` returns [TaskResult.TIMED_OUT]. */
    SAVE_TIMEOUT,

    /** The next `readConfigFromDevice()` returns [TaskResult.TIMED_OUT]. */
    READ_TIMEOUT,

    /** The next `addMix()` fails (channel full). */
    ADD_MIX_FAILS,

    /** Emits [RadioEvent.LowVoltage] and drops the simulated TX voltage below the alarm threshold. */
    LOW_TX_VOLTAGE,

    /** Emits [RadioEvent.FirmwareVersionMismatch]. */
    FIRMWARE_MISMATCH,

    /** Emits a failing [RadioEvent.PreFlightCheck]. */
    PRE_FLIGHT_CHECK_FAILS,

    /**
     * The running connection breaks: `connection` becomes [ConnectionState.Error] with
     * [ConnectionError.ConnectionLost]. The next `start()` reconnects and re-reads the simulated
     * device config (unsaved working-config changes are lost, as on the real radio).
     */
    CONNECTION_LOST,
}

/**
 * Controls of the simulator backend (`FakeRadio`), exposed through [Radio.simulator]; `null` on
 * the real radio. Used by the simulator panel, by previews and by tests.
 *
 * The simulator runs the reference mixer (`MixerMath` semantics) on its inputs, so channel
 * outputs in [Radio.live] reflect the configured mixes, curves, dual rates, limits and channel
 * locks. Telemetry values are sent as real CRSF frames through [Radio.events] so that the app's
 * decoders are exercised.
 *
 * All functions are thread-safe and take effect with the next live frame.
 */
interface SimulatorControl {

    /** True while the simulator moves sticks / telemetry by itself (demo flight). */
    val demoMotion: StateFlow<Boolean>

    /** True while the simulated receiver link is up. */
    val linkUp: StateFlow<Boolean>

    /**
     * The telemetry values currently simulated, by sensor id. Throttled like
     * `TelemetryRepository.sensors`: it emits at most about 4 times per second even while the
     * demo motion changes values on every tick, so a panel may collect it as a whole.
     */
    val telemetry: StateFlow<Map<SensorId, Double>>

    /** Turns the automatic demo motion on or off. Manual inputs keep working either way. */
    fun setDemoMotion(enabled: Boolean)

    /**
     * Sets the logic value of any input source in percent (−100..+100, coerced). This is the
     * general form; the functions below are conveniences.
     */
    fun setSource(source: SourceId, percent: Int)

    /** Sets a stick axis (Rud / Ele / Thr / Ail) in percent. */
    fun setStick(source: SourceId, percent: Int)

    /** Sets a knob or slider (P1–P8, S1–S8) in percent. */
    fun setPot(source: SourceId, percent: Int)

    /** Moves a switch (SA–SP) to [position]. */
    fun setSwitch(source: SourceId, position: SwitchPosition)

    /** Sets one of the four trims in percent; [source] is the stick axis the trim belongs to. */
    fun setTrim(source: SourceId, percent: Int)

    /** Current logic value of [source] as the simulator holds it. */
    fun sourceValue(source: SourceId): Int

    /**
     * Sets a simulated telemetry value; unknown ids are ignored. Values are in the sensor's unit.
     *
     * - `crsf.*` sensors of [org.openax12.core.model.Sensors] (GPS, vario, battery, baro altitude,
     *   airspeed, attitude, link statistics): sent as CRSF frames. They do **not** alter
     *   [org.openax12.core.model.RadioStatus] — `crsf.link.*` and `crsf.batt.*` only feed the
     *   app's CRSF decoders.
     * - `tx.batt`: [org.openax12.core.model.RadioStatus.txVoltage] (same as [setTxVoltage]).
     * - `tx.rxbatt`, `tx.lq`, `tx.rssi`: [org.openax12.core.model.RadioStatus.rxVoltage] /
     *   `linkQuality` / `rssiDbm` as shown in the status bar, while the link is up ([setLinkUp];
     *   with the link down LQ stays 0). Setting `tx.lq` to 0 is not a link drop — use [setLinkUp].
     */
    fun setTelemetry(sensor: SensorId, value: Double)

    /** Sets the flight-mode text sent in the CRSF flight-mode frame. */
    fun setTelemetryFlightMode(text: String)

    /** Sets the simulated transmitter battery voltage in volts. */
    fun setTxVoltage(volts: Float)

    /**
     * Drops (`false`) or restores (`true`) the receiver link: LQ goes to 0 / back, telemetry frames
     * stop / resume, and [RadioEvent.TelemetryLost] / [RadioEvent.TelemetryRecovered] are emitted.
     */
    fun setLinkUp(up: Boolean)

    /** Triggers [fault] (see [SimulatedFault] for when it takes effect). */
    fun injectFault(fault: SimulatedFault)

    /** Injects an arbitrary event into [Radio.events]. */
    fun emitEvent(event: RadioEvent)

    /** Resets inputs, telemetry, link and pending faults to their defaults. The configuration is kept. */
    fun reset()
}
