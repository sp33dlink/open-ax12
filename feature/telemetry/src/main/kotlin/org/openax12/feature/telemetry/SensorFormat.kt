package org.openax12.feature.telemetry

import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.UnitConversion
import org.openax12.core.model.UnitSystem

/** The value with its unit in [units], or the "no value" dashes while the sensor has not reported. */
internal fun formatSensorValue(value: SensorValue?, units: UnitSystem): String =
    if (value == null) UnitConversion.NO_VALUE else UnitConversion.format(value, units)

/** [bound] (a min or max of [value]) in [units], number only; empty for textual sensors. */
internal fun formatSensorBound(bound: Double, value: SensorValue?, units: UnitSystem): String {
    if (value == null || value.unit == SensorUnit.TEXT || bound.isNaN()) return if (value?.unit == SensorUnit.TEXT) "" else UnitConversion.NO_VALUE
    val display = UnitConversion.convert(bound, value.unit, units, value.precision)
    return UnitConversion.formatNumber(display.value, display.precision)
}

/** Short label of a unit for pickers: its symbol, or a lower-case name for the symbol-less units. */
internal fun SensorUnit.pickerLabel(): String = symbol.ifEmpty { name.lowercase() }
