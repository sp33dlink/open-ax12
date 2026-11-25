package org.openax12.feature.modelsetup.curves

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.engine.CurveIssue
import org.openax12.core.engine.CurveMath
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveType
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SourceId
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.logic.CurveEdit
import org.openax12.feature.modelsetup.logic.CurveUse
import org.openax12.feature.modelsetup.logic.ManagedKind
import org.openax12.feature.modelsetup.logic.curveUsage
import org.openax12.feature.modelsetup.logic.defaultMarkerSource

/**
 * State of the curve editor.
 *
 * @property curve the curve being edited: the draft while a gesture or a write is under way,
 *   otherwise the working configuration's curve
 * @property selectedPoint the selected point of a custom curve
 * @property managed who generated the curve, null for a user curve
 * @property locked a generated curve whose warning was not accepted yet: the editor is read-only
 * @property markerSource the input whose live value moves the marker on the curve
 * @property issues problems that keep the draft from being sent to the radio
 */
internal data class CurveEditorState(
    val ready: Boolean = false,
    val index: Int = 0,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val curve: CurveConfig = CurveConfig.empty(0),
    val selectedPoint: Int = 0,
    val managed: ManagedKind? = null,
    val modified: Boolean = false,
    val locked: Boolean = false,
    val uses: List<CurveUse> = emptyList(),
    val markerSource: SourceId = SourceId.AIL,
    val issues: List<CurveIssue> = emptyList(),
)

/**
 * View model of the curve editor ([org.openax12.core.navigation.CurveEditor]).
 *
 * Dragging a point or the parameter slider keeps the value in a local draft and sends each step
 * with `edit(refresh = false)` (the latest one only, never a backlog); finishing the gesture
 * sends one normal edit, which also refreshes the working configuration. Discrete changes
 * (type, numeric entry, add / remove point) are single normal edits.
 */
internal class CurveEditorViewModel(
    private val index: Int,
    session: ConfigSession,
    private val radio: Radio,
    extras: StateFlow<ModelExtras>,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    private val draft = MutableStateFlow<CurveConfig?>(null)
    private val selected = MutableStateFlow(0)
    private val markerOverride = MutableStateFlow<SourceId?>(null)
    private val acknowledged = MutableStateFlow(false)
    private val pushes = Channel<CurveConfig>(Channel.CONFLATED)

    init {
        scope.launch {
            for (curve in pushes) session.edit(refresh = false) { it.setCurve(curve) }
        }
    }

    /** State for the screen. */
    val state: StateFlow<CurveEditorState> = combine(
        combine(session.workingConfig, extras, draft) { config, ex, d -> Triple(config, ex, d) },
        combine(selected, markerOverride, acknowledged) { s, m, a -> Triple(s, m, a) },
    ) { (config, ex, d), (point, marker, ack) ->
        val curve = d ?: config.curve(index) ?: CurveConfig.empty(index)
        val record = ex.managed.curves.firstOrNull { it.index == index }
        val kind = ManagedKind.of(record?.generator)
        CurveEditorState(
            ready = !config.isEmpty && index in 0 until CURVE_COUNT,
            index = index,
            config = config,
            curve = curve,
            selectedPoint = point.coerceIn(0, (curve.pointCount - 1).coerceAtLeast(0)),
            managed = kind,
            modified = record != null && ex.managed.isModified(record, config),
            locked = kind != null && !ack,
            uses = curveUsage(config, index),
            markerSource = marker ?: defaultMarkerSource(config, index),
            issues = CurveMath.validate(curve),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), CurveEditorState(index = index))

    /**
     * The radio's own evaluation of the curve at the marker input (`Radio.applyCurve`, assumption
     * 19), about 8 times per second, null while there is nothing to show. Shown next to the
     * app's own value so a difference between the two models is visible.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val firmwareMarker: StateFlow<Double?> = state
        .map { it.markerSource }
        .distinctUntilChanged()
        .flatMapLatest { source -> markerValues(source) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(FlowPreview::class)
    private fun markerValues(source: SourceId): Flow<Double?> =
        radio.live.sample(MARKER_PERIOD_MS).map { frame ->
            if (state.value.curve.isUnused) null else radio.applyCurve(index, frame.logic(source).toDouble())
        }

    /** The user accepted to edit a generated curve by hand. */
    fun acknowledge() {
        acknowledged.value = true
    }

    /** Selects point [i] of a custom curve. */
    fun select(i: Int) {
        selected.value = i
    }

    /** Chooses the input that moves the marker. */
    fun setMarkerSource(source: SourceId) {
        markerOverride.value = source
    }

    /** Switches the type; a custom curve starts as the shape of the one it replaces. */
    fun setType(type: CurveType) {
        applyNow(CurveEdit.withType(current(), type))
    }

    /** A continuous change of the parameter (slider): previewed, not finished. */
    fun dragParameter(value: Int) {
        preview(CurveEdit.withParameter(current(), value))
    }

    /** Sets the parameter in one edit (number pad, function picker). */
    fun setParameter(value: Int) {
        applyNow(CurveEdit.withParameter(current(), value))
    }

    /** A continuous move of point [i] (drag); the radio follows live. */
    fun dragPoint(i: Int, x: Int, y: Int) {
        selected.value = i
        preview(CurveEdit.movePoint(current(), i, x, y))
    }

    /** The gesture ended: the draft becomes the real value. */
    fun finishDrag() {
        commit()
    }

    /** Numeric entry of the x value of point [i]. */
    fun setPointX(i: Int, x: Int) {
        val c = current()
        c.points.getOrNull(i)?.let { applyNow(CurveEdit.movePoint(c, i, x, it.y)) }
    }

    /** Numeric entry of the y value of point [i]. */
    fun setPointY(i: Int, y: Int) {
        val c = current()
        c.points.getOrNull(i)?.let { applyNow(CurveEdit.movePoint(c, i, it.x, y)) }
    }

    /** Inserts a point after the selected one. */
    fun addPoint() {
        val next = CurveEdit.addPoint(current(), selected.value)
        if (next == null) {
            message(R.string.curve_cannot_add)
        } else {
            selected.update { it + 1 }
            applyNow(next)
        }
    }

    /** Removes the selected point. */
    fun removePoint() {
        val next = CurveEdit.removePoint(current(), selected.value)
        if (next == null) message(R.string.curve_cannot_remove) else applyNow(next)
    }

    /** Resets a custom curve to a straight line with the same number of points. */
    fun straighten() {
        val c = current()
        if (c.type == CurveType.CUSTOM) applyNow(CurveEdit.identity(index, c.pointCount))
    }

    private fun current(): CurveConfig = draft.value ?: state.value.config.curve(index) ?: CurveConfig.empty(index)

    private fun preview(curve: CurveConfig) {
        draft.value = curve
        if (CurveMath.validate(curve).isEmpty()) pushes.trySend(curve)
    }

    private fun applyNow(curve: CurveConfig) {
        draft.value = curve
        commit()
    }

    private fun commit() {
        val curve = draft.value ?: return
        scope.launch {
            if (CurveMath.validate(curve).isEmpty()) {
                val ok = session.edit { it.setCurve(curve) }
                if (!ok) message(R.string.setup_write_failed)
                if (ok && curve.type == CurveType.NONE) {
                    session.updateExtras { it.copy(managed = it.managed.withoutCurve(index)) }
                }
            }
            if (draft.value == curve) draft.value = null
        }
    }

    private companion object {
        const val MARKER_PERIOD_MS = 120L
    }
}
