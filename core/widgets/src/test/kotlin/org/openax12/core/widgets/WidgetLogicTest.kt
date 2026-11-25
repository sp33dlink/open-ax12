package org.openax12.core.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.SensorId
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SensorValue
import org.openax12.core.model.Sensors
import org.openax12.core.model.SourceId
import org.openax12.core.model.UnitSystem
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WidgetOptions
import org.openax12.core.model.WidgetType

class WidgetLogicTest {

    private val metric = UnitSystem.METRIC

    // --- Readings -----------------------------------------------------------------------------

    @Test
    fun `no sensor value is a missing reading`() {
        val reading = Readings.of(Sensors.BARO_ALTITUDE, null, metric, nowMs = 1000, staleAfterMs = 3000)
        assertEquals(ReadingStatus.MISSING, reading.status)
        assertEquals("--", reading.number)
        assertFalse(reading.hasContent)
    }

    @Test
    fun `fresh value is formatted with its precision and unit`() {
        val value = SensorValue(11.42, unit = SensorUnit.VOLT, precision = 1, min = 10.9, max = 12.5, timestampMs = 900)
        val reading = Readings.of(Sensors.BATTERY_VOLTAGE, value, metric, nowMs = 1000, staleAfterMs = 3000)
        assertEquals(ReadingStatus.OK, reading.status)
        assertEquals("11.4", reading.number)
        assertEquals("V", reading.unit)
        assertEquals("10.9", reading.minText)
        assertEquals("12.5", reading.maxText)
    }

    @Test
    fun `old telemetry value is stale and a lost one is lost`() {
        val old = SensorValue(5.0, unit = SensorUnit.VOLT, timestampMs = 1000)
        assertEquals(ReadingStatus.STALE, Readings.of(Sensors.BATTERY_VOLTAGE, old, metric, nowMs = 9000, staleAfterMs = 3000).status)
        assertEquals(ReadingStatus.OK, Readings.of(Sensors.BATTERY_VOLTAGE, old, metric, nowMs = 9000, staleAfterMs = 0).status)
        // Internal values never go stale.
        assertEquals(ReadingStatus.OK, Readings.of(Sensors.TX_BATTERY, old, metric, nowMs = 9000, staleAfterMs = 3000).status)
        assertEquals(ReadingStatus.LOST, Readings.of(Sensors.BATTERY_VOLTAGE, old.copy(lost = true), metric, nowMs = 1100, staleAfterMs = 3000).status)
    }

    @Test
    fun `imperial conversion applies to value and extremes`() {
        val value = SensorValue(100.0, unit = SensorUnit.METER, min = 0.0, max = 200.0, timestampMs = 1)
        val reading = Readings.of(Sensors.BARO_ALTITUDE, value, UnitSystem.IMPERIAL, nowMs = 1, staleAfterMs = 0)
        assertEquals("328", reading.number)
        assertEquals("ft", reading.unit)
        assertEquals("656", reading.maxText)
    }

    @Test
    fun `text sensor shows its text`() {
        val reading = Readings.of(Sensors.FC_FLIGHT_MODE, SensorValue(text = "ACRO", unit = SensorUnit.TEXT), metric, 0, 0)
        assertTrue(reading.isText)
        assertEquals("ACRO", reading.number)
        assertEquals(ReadingStatus.OK, reading.status)
    }

    @Test
    fun `worst status wins`() {
        assertEquals(ReadingStatus.LOST, Readings.worst(ReadingStatus.OK, ReadingStatus.LOST, ReadingStatus.STALE))
        assertEquals(ReadingStatus.MISSING, Readings.worst(ReadingStatus.MISSING, ReadingStatus.LOST))
    }

    // --- Scale --------------------------------------------------------------------------------

    private fun config(vararg options: Pair<String, String>) = WidgetConfig(id = "w", type = WidgetType.BAR, options = mapOf(*options))

    @Test
    fun `scale defaults depend on the sensor`() {
        val percent = SensorValue(40.0, unit = SensorUnit.PERCENT)
        val percentReading = Readings.of(Sensors.BATTERY_REMAINING, percent, metric, 0, 0)
        assertEquals(Scale(0.0, 100.0), Scale.resolve(config(), Sensors.BATTERY_REMAINING, percent, percentReading, metric))

        val stick = SensorId.input(SourceId.THR)
        val live = SensorValue(40.0, unit = SensorUnit.PERCENT)
        assertEquals(Scale(-100.0, 100.0), Scale.resolve(config(), stick, live, Readings.of(stick, live, metric, 0, 0), metric))

        val volts = SensorValue(11.4, unit = SensorUnit.VOLT, precision = 1, min = 10.9, max = 12.5)
        val scale = Scale.resolve(config(), Sensors.BATTERY_VOLTAGE, volts, Readings.of(Sensors.BATTERY_VOLTAGE, volts, metric, 0, 0), metric)
        assertEquals(0.0, scale.min, 1e-9)
        assertEquals(20.0, scale.max, 1e-9)
    }

    @Test
    fun `scale options and warnings are honoured`() {
        val volts = SensorValue(11.4, unit = SensorUnit.VOLT, precision = 1)
        val reading = Readings.of(Sensors.BATTERY_VOLTAGE, volts, metric, 0, 0)
        val scale = Scale.resolve(
            config(WidgetOptions.MIN to "9", WidgetOptions.MAX to "12.6", WidgetOptions.WARN_BELOW to "11.5"),
            Sensors.BATTERY_VOLTAGE, volts, reading, metric,
        )
        assertEquals(9.0, scale.min, 1e-9)
        assertEquals(12.6, scale.max, 1e-9)
        assertTrue(scale.isWarn(11.4))
        assertFalse(scale.isWarn(11.6))
        assertEquals(2.4f / 3.6f, scale.fraction(11.4), 1e-4f)
        assertEquals(1f, scale.fraction(99.0), 0f)
        assertEquals(0f, scale.fraction(Double.NaN), 0f)
    }

    @Test
    fun `bipolar scale fills from zero`() {
        val scale = Scale(-5.0, 5.0)
        assertTrue(scale.isBipolar)
        assertEquals(0.5f, scale.originFraction, 1e-6f)
        assertEquals(0f, Scale(0.0, 10.0).originFraction, 0f)
    }

    @Test
    fun `nice ceiling rounds up to 1 2 2_5 5 10`() {
        assertEquals(20.0, Scale.niceCeil(12.6), 1e-9)
        assertEquals(200.0, Scale.niceCeil(187.0), 1e-9)
        assertEquals(5.0, Scale.niceCeil(4.6), 1e-9)
        assertEquals(0.25, Scale.niceCeil(0.21), 1e-9)
        assertEquals(1.0, Scale.niceCeil(0.0), 1e-9)
    }

    // --- Formatting ---------------------------------------------------------------------------

    @Test
    fun `format helpers`() {
        assertEquals("120", WidgetFormat.scaleLabel(120.0))
        assertEquals("12.6", WidgetFormat.scaleLabel(12.6))
        assertEquals("48.13712° N", WidgetFormat.coordinate(48.13712, "N", "S"))
        assertEquals("11.57541° W", WidgetFormat.coordinate(-11.57541, "E", "W"))
        assertEquals("152" to "m", WidgetFormat.distance(152.0, UnitSystem.METRIC))
        assertEquals("1.52" to "km", WidgetFormat.distance(1520.0, UnitSystem.METRIC))
        assertEquals("499" to "ft", WidgetFormat.distance(152.0, UnitSystem.IMPERIAL))
        assertEquals(0, WidgetFormat.compassOctant(350.0))
        assertEquals(5, WidgetFormat.compassOctant(235.0))
        assertEquals(6, WidgetFormat.compassOctant(-90.0))
        assertEquals(0xFF1FB8A6, WidgetFormat.parseColor("#1FB8A6"))
        assertNull(WidgetFormat.parseColor("teal"))
    }

    @Test
    fun `signed adds a plus to positive numbers only`() {
        fun reading(v: Double) = Readings.of(Sensors.VERTICAL_SPEED, SensorValue(v, unit = SensorUnit.METER_PER_SECOND, precision = 1), metric, 0, 0)
        assertEquals("+1.8", WidgetFormat.signed(reading(1.8)))
        assertEquals("-2.4", WidgetFormat.signed(reading(-2.4)))
        assertEquals("0.0", WidgetFormat.signed(reading(0.0)))
        assertEquals("--", WidgetFormat.signed(Reading.MISSING))
    }

    // --- Battery ------------------------------------------------------------------------------

    @Test
    fun `battery cell detection and charge`() {
        assertEquals(3, BatteryMath.cellCount(0, 11.4, 12.5))
        assertEquals(4, BatteryMath.cellCount(0, 14.8, Double.NaN))
        assertEquals(6, BatteryMath.cellCount(6, 11.4, 12.5))
        assertEquals(0, BatteryMath.cellCount(0, Double.NaN, Double.NaN))
        assertEquals(100, BatteryMath.percent(4.2))
        assertEquals(0, BatteryMath.percent(3.0))
        assertEquals(40, BatteryMath.percent(3.80))
        assertEquals(-1, BatteryMath.percent(Double.NaN))
    }

    // --- Graph --------------------------------------------------------------------------------

    @Test
    fun `graph range pads the data and honours options`() {
        val auto = GraphRange.of(doubleArrayOf(10.0, 20.0, 15.0), 3, Double.NaN, Double.NaN)
        assertTrue(auto.min < 10.0 && auto.min >= 0.0)
        assertTrue(auto.max > 20.0)
        val fixed = GraphRange.of(doubleArrayOf(10.0, 20.0), 2, 0.0, 50.0)
        assertEquals(GraphRange(0.0, 50.0), fixed)
        val flat = GraphRange.of(doubleArrayOf(7.0, 7.0), 2, Double.NaN, Double.NaN)
        assertTrue(flat.max > flat.min)
        val empty = GraphRange.of(DoubleArray(0), 0, Double.NaN, Double.NaN)
        assertTrue(empty.max > empty.min)
    }

    // --- Grid ---------------------------------------------------------------------------------

    @Test
    fun `grid geometry places cells and spans`() {
        val grid = WidgetGridGeometry(width = 620f, height = 410f, columns = 6, rows = 4, spacing = 4f)
        assertEquals(100f, grid.cellWidth, 1e-3f)
        assertEquals(99.5f, grid.cellHeight, 1e-3f)
        val rect = grid.rect(1, 2, 2, 2)
        assertEquals(104f, rect.left, 1e-3f)
        assertEquals(204f, rect.width, 1e-3f)
        assertEquals(207f, rect.top, 1e-3f)
        assertEquals(IntOffset(1, 2), grid.cellAt(Offset(150f, 250f)))
        assertEquals(IntOffset(5, 3), grid.cellAt(Offset(620f, 410f)))
        assertNull(grid.cellAt(Offset(-1f, 10f)))
        assertEquals(IntOffset(2, -1), grid.cellsFor(Offset(210f, -100f)))
    }

    @Test
    fun `widgets are cut off at the grid edge or dropped`() {
        val grid = WidgetGridGeometry(600f, 400f, 6, 4, 0f)
        val inside = grid.rectOf(WidgetConfig(id = "a", x = 4, y = 3, w = 3, h = 2))
        assertNotNull(inside)
        assertEquals(200f, inside!!.width, 1e-3f)
        assertEquals(100f, inside.height, 1e-3f)
        assertNull(grid.rectOf(WidgetConfig(id = "b", x = 6, y = 0)))
        assertNull(grid.rectOf(WidgetConfig(id = "c", x = 0, y = 9)))
        assertNull(WidgetGridGeometry(0f, 0f, 6, 4, 0f).rectOf(WidgetConfig(id = "d")))
    }

    @Test
    fun `pinned arrangements cover the card without overlap`() {
        for (count in 1..PinnedArrangement.MAX_PINNED) {
            for (aspect in floatArrayOf(0.5f, 1f, 1.4f, 3.5f)) {
                val arrangement = PinnedArrangement.of(count, aspect)
                assertEquals(count, arrangement.slots.size)
                val covered = HashSet<Pair<Int, Int>>()
                for (slot in arrangement.slots) {
                    for (x in slot.x until slot.x + slot.w) {
                        for (y in slot.y until slot.y + slot.h) {
                            assertTrue("overlap for $count at $aspect", covered.add(x to y))
                            assertTrue(x < arrangement.columns && y < arrangement.rows)
                        }
                    }
                }
                assertEquals("gaps for $count at $aspect", arrangement.columns * arrangement.rows, covered.size)
            }
        }
        assertEquals(PinnedArrangement.MAX_PINNED, PinnedArrangement.of(9, 1f).slots.size)
        assertTrue(PinnedArrangement.of(0, 1f).slots.isEmpty())
    }

    // --- Catalog and fake data ----------------------------------------------------------------

    @Test
    fun `catalog describes every widget type`() {
        assertEquals(WidgetType.entries.toSet(), WidgetCatalog.types.map { it.type }.toSet())
        assertEquals(WidgetType.entries.size, WidgetCatalog.types.size)
        for (info in WidgetCatalog.types) {
            assertEquals(info.type, info.preview.type)
            assertTrue(info.defaultWidth >= 1 && info.defaultHeight >= 1)
            val created = WidgetCatalog.create(info.type, "id", x = 2, y = 1)
            assertEquals(info.defaultWidth, created.w)
            assertEquals(info.defaultHeight, created.h)
            assertEquals(2, created.x)
            assertTrue(created.sensors.size <= info.sensorSlots.size)
            for (slot in info.sensorSlots.filter { it.required }) assertNotNull("${info.type} needs a default", slot.default)
            assertEquals(info.options.size, info.options.map { it.key }.toSet().size)
        }
    }

    @Test
    fun `fake data source serves what was set`() {
        val source = FakeWidgetDataSource(now = 50_000)
        assertNull(source.sensor(Sensors.BARO_ALTITUDE).initial())
        source.set(Sensors.BARO_ALTITUDE, 120.0, SensorUnit.METER, name = "Alt", min = 0.0, max = 150.0)
        val value = source.sensor(Sensors.BARO_ALTITUDE).initial()
        assertEquals(120.0, value!!.value, 0.0)
        assertEquals("Alt", source.sensorInfo(Sensors.BARO_ALTITUDE)?.name)
        val history = source.history(Sensors.BARO_ALTITUDE, 40)
        assertTrue(history.size > 10)
        assertEquals(120.0, history.last().value, 1e-6)
        assertTrue(history.all { it.value in 0.0..150.0 && it.timestampMs in 0..50_000 })
        source.setLost(Sensors.BARO_ALTITUDE)
        assertTrue(source.sensor(Sensors.BARO_ALTITUDE).initial()!!.lost)
        source.remove(Sensors.BARO_ALTITUDE)
        assertNull(source.sensor(Sensors.BARO_ALTITUDE).initial())
        assertEquals("Height 123 m", FakeWidgetDataSource.demo().resolveText("Height {Alt} m"))
        assertEquals("{nope}", FakeWidgetDataSource.demo().resolveText("{nope}"))
        assertTrue(FakeWidgetDataSource.demo().logicalSwitches.initial()[0])
    }

    @Test
    fun `maps uri`() {
        assertEquals("geo:48.137120,11.575410?q=48.137120,11.575410", gpsMapsUri(48.13712, 11.57541))
    }
}
