package org.openax12.core.engine

import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CURVE_MAX_POINTS
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurvePoint
import org.openax12.core.model.CurveType
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToInt

/** A problem that makes a curve unusable. */
enum class CurveIssue {
    /** A custom curve needs at least two points. */
    TOO_FEW_POINTS,

    /** More than `CURVE_MAX_POINTS` points. */
    TOO_MANY_POINTS,

    /** The x values of a custom curve must be strictly ascending. */
    X_NOT_ASCENDING,

    /** A coordinate or parameter is outside −100..+100. */
    VALUE_OUT_OF_RANGE,

    /** The curve index is outside 0..`CURVE_COUNT`-1. */
    INVALID_INDEX,
}

/**
 * Reference evaluation of every curve type, used by the simulator, the curve editor preview and
 * the "explain" view. On the real radio the firmware's own result is available through
 * `Radio.applyCurve`; this implementation is the app's best model of it (assumption 12).
 *
 * All values are percentages. Inputs are clamped to −100..+100 first.
 *
 * - `NONE`: identity.
 * - `DIFF` (parameter d = `y[0]`, −100..+100): differential. For d > 0 the negative half is
 *   scaled by (100 − d) %, for d < 0 the positive half is scaled by (100 + d) %.
 * - `EXPO` (parameter k = `y[0]`, −100..+100): `y = x · (1 − k') + x³ · k'` on the normalized
 *   input with k' = |k| / 100 for k ≥ 0; for k < 0 the inverse shape `y = x · (1 − k') + ∛x · k'`
 *   (more sensitive around centre).
 * - `FUNC` (parameter = function number in `y[0]`): 0 `x>0` (negative half → 0), 1 `x<0`
 *   (positive half → 0), 2 `|x|`, 3 `f>0` (x > 0 → +100 else 0), 4 `f<0` (x < 0 → −100 else 0),
 *   5 `|f|` (x > 0 → +100, x < 0 → −100, 0 → 0). Unknown numbers = identity.
 * - `CUSTOM`: linear interpolation between the points; outside the first / last x the curve
 *   continues flat at the first / last y.
 */
object CurveMath {

    /** Function numbers of a `FUNC` curve (the curve's parameter). */
    const val FUNC_POSITIVE_HALF: Int = 0
    /** `x<0`: only the negative half passes. */
    const val FUNC_NEGATIVE_HALF: Int = 1
    /** `|x|`. */
    const val FUNC_ABS: Int = 2
    /** `f>0`: +100 for a positive input, else 0. */
    const val FUNC_STEP_POSITIVE: Int = 3
    /** `f<0`: −100 for a negative input, else 0. */
    const val FUNC_STEP_NEGATIVE: Int = 4
    /** `|f|`: sign of the input times 100. */
    const val FUNC_SIGN: Int = 5

    /** Output of [curve] for input [x] (percent). Invalid curves evaluate as identity. */
    fun apply(curve: CurveConfig, x: Double): Double {
        if (x.isNaN()) return 0.0
        val input = x.coerceIn(PERCENT_MIN.toDouble(), PERCENT_MAX.toDouble())
        return when (curve.type) {
            CurveType.NONE -> input
            CurveType.DIFF -> diff(input, curve.parameter.coerceIn(PERCENT_MIN, PERCENT_MAX))
            CurveType.EXPO -> expo(input, curve.parameter.coerceIn(PERCENT_MIN, PERCENT_MAX))
            CurveType.FUNC -> function(input, curve.parameter)
            CurveType.CUSTOM -> custom(input, curve)
        }
    }

    /** [steps] + 1 evenly spaced samples from x = −100 to x = +100, for drawing the curve. */
    fun sample(curve: CurveConfig, steps: Int = 100): List<CurvePoint> {
        val count = steps.coerceIn(1, 2000)
        return List(count + 1) { i ->
            val x = PERCENT_MIN + 200.0 * i / count
            CurvePoint(x.roundToInt(), apply(curve, x).roundToInt())
        }
    }

    /** Problems of [curve]; empty = valid and safe to send to the radio. */
    fun validate(curve: CurveConfig): List<CurveIssue> {
        val issues = LinkedHashSet<CurveIssue>()
        if (curve.index !in 0 until CURVE_COUNT) issues += CurveIssue.INVALID_INDEX
        val range = PERCENT_MIN..PERCENT_MAX
        when (curve.type) {
            CurveType.NONE -> Unit
            CurveType.DIFF, CurveType.EXPO, CurveType.FUNC ->
                if (curve.parameter !in range) issues += CurveIssue.VALUE_OUT_OF_RANGE
            CurveType.CUSTOM -> {
                if (curve.pointCount < 2) issues += CurveIssue.TOO_FEW_POINTS
                if (curve.pointCount > CURVE_MAX_POINTS) issues += CurveIssue.TOO_MANY_POINTS
                val points = curve.points
                if (points.zipWithNext().any { (a, b) -> b.x <= a.x }) issues += CurveIssue.X_NOT_ASCENDING
                if (points.any { it.x !in range || it.y !in range }) issues += CurveIssue.VALUE_OUT_OF_RANGE
            }
        }
        return issues.toList()
    }

    /**
     * A custom curve with [count] points (2..12) evenly spaced in x that approximates [curve]
     * (used when converting an expo / diff curve to an editable custom curve).
     */
    fun toCustom(curve: CurveConfig, count: Int = 9): CurveConfig {
        val n = count.coerceIn(2, CURVE_MAX_POINTS)
        val points = List(n) { i ->
            val x = (PERCENT_MIN + 200.0 * i / (n - 1)).roundToInt()
            CurvePoint(x, apply(curve, x.toDouble()).roundToInt().coerceIn(PERCENT_MIN, PERCENT_MAX))
        }
        return CurveConfig.custom(curve.index, points)
    }

    /**
     * Output of a custom curve given as plain points (the same interpolation as [apply]); used by
     * the compilers to predict planned curves. Fewer than two points or non-ascending x = identity.
     */
    fun interpolate(points: List<CurvePoint>, x: Double): Double {
        val input = x.coerceIn(PERCENT_MIN.toDouble(), PERCENT_MAX.toDouble())
        if (points.size < 2 || points.zipWithNext().any { (a, b) -> b.x <= a.x }) return input
        if (input <= points.first().x) return points.first().y.toDouble()
        if (input >= points.last().x) return points.last().y.toDouble()
        for (i in 1 until points.size) {
            val right = points[i]
            if (input <= right.x) {
                val left = points[i - 1]
                val t = (input - left.x) / (right.x - left.x).toDouble()
                return left.y + (right.y - left.y) * t
            }
        }
        return points.last().y.toDouble()
    }

    private fun diff(x: Double, d: Int): Double = when {
        d > 0 && x < 0 -> x * (100 - d) / 100.0
        d < 0 && x > 0 -> x * (100 + d) / 100.0
        else -> x
    }

    private fun expo(x: Double, k: Int): Double {
        val n = x / 100.0
        val a = abs(n)
        val f = abs(k) / 100.0
        // Negative expo blends towards the cube root: steep around the centre, flat towards the
        // ends. The simulator (`SimCurves` in `:core:radio`) uses the same formula.
        val shaped = if (k >= 0) a * (1 - f) + a * a * a * f else a * (1 - f) + cbrt(a) * f
        return (if (n < 0) -shaped else shaped) * 100.0
    }

    private fun function(x: Double, number: Int): Double = when (number) {
        FUNC_POSITIVE_HALF -> if (x > 0) x else 0.0
        FUNC_NEGATIVE_HALF -> if (x < 0) x else 0.0
        FUNC_ABS -> abs(x)
        FUNC_STEP_POSITIVE -> if (x > 0) 100.0 else 0.0
        FUNC_STEP_NEGATIVE -> if (x < 0) -100.0 else 0.0
        FUNC_SIGN -> if (x > 0) 100.0 else if (x < 0) -100.0 else 0.0
        else -> x
    }

    private fun custom(x: Double, curve: CurveConfig): Double {
        if (curve.pointCount !in 2..CURVE_MAX_POINTS) return x
        return interpolate(curve.points, x)
    }
}
