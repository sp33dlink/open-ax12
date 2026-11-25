package org.openax12.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType

class CurveMathTest {

    @Test
    fun `no curve is the identity and clamps the input`() {
        val none = CurveConfig.empty(0)
        assertEquals(37.5, CurveMath.apply(none, 37.5), EPS)
        assertEquals(100.0, CurveMath.apply(none, 150.0), EPS)
        assertEquals(-100.0, CurveMath.apply(none, -250.0), EPS)
        assertEquals(0.0, CurveMath.apply(none, Double.NaN), EPS)
    }

    @Test
    fun `differential scales one half only`() {
        val positive = CurveConfig.parametric(0, CurveType.DIFF, 30)
        assertEquals(-70.0, CurveMath.apply(positive, -100.0), EPS)
        assertEquals(-35.0, CurveMath.apply(positive, -50.0), EPS)
        assertEquals(50.0, CurveMath.apply(positive, 50.0), EPS)

        val negative = CurveConfig.parametric(0, CurveType.DIFF, -30)
        assertEquals(70.0, CurveMath.apply(negative, 100.0), EPS)
        assertEquals(-100.0, CurveMath.apply(negative, -100.0), EPS)
    }

    @Test
    fun `expo flattens the centre and keeps the end points`() {
        val expo = CurveConfig.parametric(0, CurveType.EXPO, 50)
        assertEquals(31.25, CurveMath.apply(expo, 50.0), EPS)
        assertEquals(-31.25, CurveMath.apply(expo, -50.0), EPS)
        assertEquals(100.0, CurveMath.apply(expo, 100.0), EPS)
        assertEquals(-100.0, CurveMath.apply(expo, -100.0), EPS)
        assertEquals(0.0, CurveMath.apply(expo, 0.0), EPS)

        val linear = CurveConfig.parametric(0, CurveType.EXPO, 0)
        assertEquals(42.0, CurveMath.apply(linear, 42.0), EPS)
    }

    @Test
    fun `negative expo is steeper around the centre and symmetric`() {
        val expo = CurveConfig.parametric(0, CurveType.EXPO, -50)
        val half = CurveMath.apply(expo, 50.0)
        assertEquals(25.0 + Math.cbrt(0.5) * 50.0, half, EPS)
        assertTrue(half > 50.0)
        assertEquals(-half, CurveMath.apply(expo, -50.0), EPS)
        assertEquals(100.0, CurveMath.apply(expo, 100.0), EPS)
        assertEquals(0.0, CurveMath.apply(expo, 0.0), EPS)
    }

    @Test
    fun `built-in functions`() {
        fun f(number: Int, x: Double) = CurveMath.apply(CurveConfig.parametric(0, CurveType.FUNC, number), x)
        assertEquals(40.0, f(CurveMath.FUNC_POSITIVE_HALF, 40.0), EPS)
        assertEquals(0.0, f(CurveMath.FUNC_POSITIVE_HALF, -40.0), EPS)
        assertEquals(0.0, f(CurveMath.FUNC_NEGATIVE_HALF, 40.0), EPS)
        assertEquals(-40.0, f(CurveMath.FUNC_NEGATIVE_HALF, -40.0), EPS)
        assertEquals(40.0, f(CurveMath.FUNC_ABS, -40.0), EPS)
        assertEquals(100.0, f(CurveMath.FUNC_STEP_POSITIVE, 1.0), EPS)
        assertEquals(0.0, f(CurveMath.FUNC_STEP_POSITIVE, 0.0), EPS)
        assertEquals(-100.0, f(CurveMath.FUNC_STEP_NEGATIVE, -1.0), EPS)
        assertEquals(0.0, f(CurveMath.FUNC_STEP_NEGATIVE, 1.0), EPS)
        assertEquals(100.0, f(CurveMath.FUNC_SIGN, 3.0), EPS)
        assertEquals(-100.0, f(CurveMath.FUNC_SIGN, -3.0), EPS)
        assertEquals(0.0, f(CurveMath.FUNC_SIGN, 0.0), EPS)
        assertEquals(12.0, f(99, 12.0), EPS)
    }

    @Test
    fun `custom curve interpolates linearly between its points`() {
        val curve = CurveConfig.custom(3, listOf(CurvePoint(-100, -50), CurvePoint(0, 0), CurvePoint(100, 100)))
        assertEquals(-50.0, CurveMath.apply(curve, -100.0), EPS)
        assertEquals(-25.0, CurveMath.apply(curve, -50.0), EPS)
        assertEquals(0.0, CurveMath.apply(curve, 0.0), EPS)
        assertEquals(50.0, CurveMath.apply(curve, 50.0), EPS)
        assertEquals(100.0, CurveMath.apply(curve, 100.0), EPS)
    }

    @Test
    fun `custom curve continues flat outside its first and last point`() {
        val curve = CurveConfig.custom(0, listOf(CurvePoint(-50, -20), CurvePoint(50, 20)))
        assertEquals(-20.0, CurveMath.apply(curve, -100.0), EPS)
        assertEquals(20.0, CurveMath.apply(curve, 100.0), EPS)
        assertEquals(0.0, CurveMath.apply(curve, 0.0), EPS)
        assertEquals(10.0, CurveMath.apply(curve, 25.0), EPS)
    }

    @Test
    fun `custom curve with twelve points`() {
        val points = List(CURVE_MAX_POINTS) { i -> CurvePoint(-100 + i * 18, if (i % 2 == 0) 0 else 40) }
        val curve = CurveConfig.custom(0, points)
        assertEquals(CURVE_MAX_POINTS, curve.pointCount)
        assertEquals(0.0, CurveMath.apply(curve, -100.0), EPS)
        assertEquals(40.0, CurveMath.apply(curve, -82.0), EPS)
        assertEquals(20.0, CurveMath.apply(curve, -91.0), EPS)
        // Flat after the last point at x = 98.
        assertEquals(40.0, CurveMath.apply(curve, 100.0), EPS)
        assertTrue(CurveMath.validate(curve).isEmpty())
    }

    @Test
    fun `invalid custom curves evaluate as identity`() {
        val onePoint = CurveConfig.custom(0, listOf(CurvePoint(0, 50)))
        assertEquals(30.0, CurveMath.apply(onePoint, 30.0), EPS)
        val unordered = CurveConfig.custom(0, listOf(CurvePoint(50, 10), CurvePoint(-50, 90)))
        assertEquals(30.0, CurveMath.apply(unordered, 30.0), EPS)
        assertEquals(30.0, CurveMath.interpolate(emptyList(), 30.0), EPS)
    }

    @Test
    fun `interpolate matches apply for plain points`() {
        val points = listOf(CurvePoint(-100, 10), CurvePoint(0, -30), CurvePoint(100, 70))
        val curve = CurveConfig.custom(0, points)
        for (x in listOf(-100.0, -33.0, 0.0, 12.5, 100.0)) {
            assertEquals(CurveMath.apply(curve, x), CurveMath.interpolate(points, x), EPS)
        }
        assertEquals(-10.0, CurveMath.interpolate(points, -50.0), EPS)
    }

    @Test
    fun `sample returns evenly spaced points`() {
        val samples = CurveMath.sample(CurveConfig.parametric(0, CurveType.FUNC, CurveMath.FUNC_ABS), steps = 4)
        assertEquals(listOf(-100, -50, 0, 50, 100), samples.map { it.x })
        assertEquals(listOf(100, 50, 0, 50, 100), samples.map { it.y })
        assertEquals(101, CurveMath.sample(CurveConfig.empty(0)).size)
        assertEquals(2, CurveMath.sample(CurveConfig.empty(0), steps = 0).size)
    }

    @Test
    fun `validate reports every kind of problem`() {
        assertTrue(CurveMath.validate(CurveConfig.empty(0)).isEmpty())
        assertEquals(listOf(CurveIssue.INVALID_INDEX), CurveMath.validate(CurveConfig.empty(18)))
        assertEquals(listOf(CurveIssue.VALUE_OUT_OF_RANGE), CurveMath.validate(CurveConfig.parametric(0, CurveType.EXPO, 120)))
        assertEquals(listOf(CurveIssue.TOO_FEW_POINTS), CurveMath.validate(CurveConfig.custom(0, listOf(CurvePoint(0, 0)))))
        assertEquals(
            listOf(CurveIssue.X_NOT_ASCENDING),
            CurveMath.validate(CurveConfig.custom(0, listOf(CurvePoint(0, 0), CurvePoint(0, 10)))),
        )
        assertEquals(
            listOf(CurveIssue.VALUE_OUT_OF_RANGE),
            CurveMath.validate(CurveConfig.custom(0, listOf(CurvePoint(-100, 0), CurvePoint(100, 130)))),
        )
        val tooMany = CurveConfig.custom(0, listOf(CurvePoint(-100, 0), CurvePoint(100, 0))).copy(pointCount = 13)
        assertTrue(CurveIssue.TOO_MANY_POINTS in CurveMath.validate(tooMany))
    }

    @Test
    fun `toCustom approximates a curve with editable points`() {
        val expo = CurveConfig.parametric(5, CurveType.EXPO, 50)
        val custom = CurveMath.toCustom(expo, 9)
        assertEquals(CurveType.CUSTOM, custom.type)
        assertEquals(5, custom.index)
        assertEquals(9, custom.pointCount)
        assertEquals(CurvePoint(-100, -100), custom.points.first())
        assertEquals(CurvePoint(0, 0), custom.points[4])
        assertEquals(CurvePoint(100, 100), custom.points.last())
        assertEquals(CurvePoint(50, 31), custom.points[6])
        assertTrue(CurveMath.validate(custom).isEmpty())
        assertEquals(CURVE_MAX_POINTS, CurveMath.toCustom(expo, 40).pointCount)
        assertEquals(2, CurveMath.toCustom(expo, 0).pointCount)
    }
}
