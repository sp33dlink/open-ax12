package org.openax12.feature.home

import androidx.compose.runtime.Immutable
import org.openax12.core.data.ModelEntry
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceKind
import org.openax12.core.model.Sources
import org.openax12.core.model.StickMode
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.WidgetConfig
import org.openax12.core.model.WorkingConfig

// Pure state of the home screen: what the ViewModel derives from the model library, the extras of
// the current model and the working configuration. Nothing here changes at the live-frame rate;
// live values (sticks, channels, switches, timers) are read straight from `Radio.live` in the
// composables that draw them.

/** Channels shown per page of the channel list (CH01-CH08, CH09-CH16). */
internal const val CHANNELS_PER_PAGE: Int = 8

/** Number of pages of the channel list. */
internal const val CHANNEL_PAGES: Int = CHANNEL_COUNT / CHANNELS_PER_PAGE

/** Switch pills of the input panel (SA-SF as in the stock app). */
internal const val SWITCH_PILLS: Int = 6

/** Arc gauges of the input panel (S1 and S2 as in the stock app). */
internal const val KNOB_GAUGES: Int = 2

/** Longest source name drawn under a pill / inside a gauge; longer user names fall back to the default name. */
private const val MAX_LABEL_LENGTH: Int = 4

/** Timers T1 and T2 are always listed (as in the stock app); T3 only when it is configured. */
private const val ALWAYS_SHOWN_TIMERS: Int = 2

/** One row of the channel list. */
@Immutable
internal data class ChannelItem(
    /** 0-based channel index. */
    val index: Int,
    /** True for channels with at least one mix line (drawn emphasised). */
    val emphasized: Boolean,
)

/** One timer row of the centre card. */
@Immutable
internal data class TimerItem(
    /** 0-based timer index (T1 = 0). */
    val index: Int,
)

/** A switch pill. */
@Immutable
internal data class SwitchItem(val source: SourceId, val label: String)

/** How the value of a knob is drawn. */
internal enum class KnobRange {
    /** -100..+100 with the zero mark at the top (pots, counters). */
    CENTERED,

    /** 0..100 filling from the left end (sliders, as in the stock app). */
    ONE_SIDED,
}

/** An arc gauge. */
@Immutable
internal data class KnobItem(val source: SourceId, val label: String, val range: KnobRange)

/**
 * The right-hand input panel, one page like the stock app: up to [SWITCH_PILLS] switch pills above up
 * to [KNOB_GAUGES] arc gauges and, when the radio has an SP input, its row of six buttons.
 *
 * Further enabled inputs (the other pots, counters, more switches) are not drawn here; they stay
 * reachable in Model setup > Input check.
 */
@Immutable
internal data class InputPanelState(
    val switches: List<SwitchItem> = emptyList(),
    val knobs: List<KnobItem> = emptyList(),
    /** The SP input is enabled: draw its button row. */
    val showSpButtons: Boolean = false,
)

/** Everything the stateless home screen needs apart from live values. */
@Immutable
internal data class HomeUiState(
    val modelName: String = "",
    val picturePath: String? = null,
    val pictureVersion: Long = 0,
    val modelType: ModelType = ModelType.GLIDER,
    val centerCard: HomeCenterCard = HomeCenterCard.PICTURE,
    val pinnedWidgets: List<WidgetConfig> = emptyList(),
    val stickMode: StickMode = StickMode.MODE_2,
    val channels: List<ChannelItem> = List(CHANNEL_COUNT) { ChannelItem(it, emphasized = false) },
    val channelPage: Int = 0,
    val timers: List<TimerItem> = List(ALWAYS_SHOWN_TIMERS) { TimerItem(it) },
    val inputs: InputPanelState = InputPanelState(),
) {
    /** The channels of the page shown. */
    val visibleChannels: List<ChannelItem>
        get() = channels.drop(channelPage * CHANNELS_PER_PAGE).take(CHANNELS_PER_PAGE)
}

/** Builds the state of the home screen. Pure: the same inputs always give the same state. */
internal fun buildHomeState(model: ModelEntry?, extras: ModelExtras, config: WorkingConfig): HomeUiState {
    return HomeUiState(
        modelName = model?.name.orEmpty(),
        picturePath = model?.picturePath,
        pictureVersion = model?.pictureVersion ?: 0,
        modelType = extras.modelType,
        centerCard = extras.home.centerCard,
        pinnedWidgets = extras.telemetry.pinnedWidgets,
        stickMode = config.system.stickMode,
        channels = List(CHANNEL_COUNT) { ChannelItem(it, emphasized = (config.channel(it)?.mixCount ?: 0) > 0) },
        channelPage = extras.home.channelPage.coerceIn(0, CHANNEL_PAGES - 1),
        timers = timerItems(config),
        inputs = planInputs(config),
    )
}

/** The timers to list: T1 and T2 always, T3 when it has a switch, a start time or a mode. */
internal fun timerItems(config: WorkingConfig): List<TimerItem> {
    val third = config.timers.firstOrNull { it.index == ALWAYS_SHOWN_TIMERS }
    val thirdUsed = third != null && (third.switch.isEnabled || third.startTime > 0 || third.mode != 0)
    val count = if (thirdUsed) TIMER_COUNT else ALWAYS_SHOWN_TIMERS
    return List(count) { TimerItem(it) }
}

/** Switches shown on the first page while the radio has not delivered its source list yet (as in the stock app: SA-SF). */
private val FallbackSwitches: List<SourceId> = ('A'..'F').map { SourceId.switch(it) }

/** Knobs shown while the source list is empty: S1 and S2. */
private val FallbackKnobs: List<SourceId> = listOf(SourceId.slider(1), SourceId.slider(2))

/**
 * Plans the input panel (SPEC section 2). Only inputs the radio reports as present are listed:
 * the first [SWITCH_PILLS] enabled switches (SA-SF on the AX12; SP is the button row, not a pill),
 * the first [KNOB_GAUGES] enabled knobs (sliders first, then pots, then counters) and the SP button
 * row when SP is enabled. Without a source list (radio not ready) the stock layout is shown, without
 * the SP row.
 */
internal fun planInputs(config: WorkingConfig): InputPanelState {
    if (config.sources.isEmpty()) {
        return InputPanelState(
            switches = FallbackSwitches.map { SwitchItem(it, sourceLabel(config, it)) },
            knobs = FallbackKnobs.map { KnobItem(it, sourceLabel(config, it), knobRange(it)) },
        )
    }
    val enabled = config.sources.filter { it.enabled }.map { it.id }
    val switchIds = enabled.filter { it.kind == SourceKind.SWITCH && it.index != Sources.SP }
    val knobIds = enabled.filter { it.kind == SourceKind.SLIDER } +
        enabled.filter { it.kind == SourceKind.POT } +
        enabled.filter { it.kind == SourceKind.COUNTER }
    return InputPanelState(
        switches = switchIds.take(SWITCH_PILLS).map { SwitchItem(it, sourceLabel(config, it)) },
        knobs = knobIds.take(KNOB_GAUGES).map { KnobItem(it, sourceLabel(config, it), knobRange(it)) },
        showSpButtons = enabled.any { it.index == Sources.SP },
    )
}

/** Pots and counters are drawn centred, sliders from the left end (assumption in docs/ASSUMPTIONS.md). */
internal fun knobRange(source: SourceId): KnobRange =
    if (source.kind == SourceKind.SLIDER) KnobRange.ONE_SIDED else KnobRange.CENTERED

/** The name drawn for a source: the user's name when it is short enough for a pill, else the firmware name. */
internal fun sourceLabel(config: WorkingConfig, source: SourceId): String =
    config.sourceName(source).takeIf { it.length <= MAX_LABEL_LENGTH } ?: source.defaultName

/**
 * Display value of a knob in percent for its [range]: centred knobs keep -100..+100, one-sided
 * knobs map -100..+100 to 0..100.
 */
internal fun knobPercent(logic: Int, range: KnobRange): Float = when (range) {
    KnobRange.CENTERED -> logic.toFloat()
    KnobRange.ONE_SIDED -> (logic + 100) / 2f
}
