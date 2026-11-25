package org.openax12.feature.home

import androidx.compose.runtime.Immutable
import org.openax12.core.data.ModelEntry
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.HomeCenterCard
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceKind
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

/** Switch pills per page of the input panel. */
internal const val SWITCHES_PER_PAGE: Int = 6

/** Arc gauges per page of the input panel. */
internal const val KNOBS_PER_PAGE: Int = 2

/** Logical-switch chips on the flight-mode page; further ones are summarised as "+n more". */
internal const val LOGIC_CHIPS_PER_PAGE: Int = 10

/** Upper bound of input pages so that the page selector always fits its column. */
internal const val MAX_INPUT_PAGES: Int = 8

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

/** A configured logical switch shown as a chip on the flight-mode page. */
@Immutable
internal data class LogicSwitchItem(val index: Int, val label: String)

/** One page of the right-hand input panel. */
internal sealed interface HomePage {

    /** Up to [SWITCHES_PER_PAGE] switch pills above up to [KNOBS_PER_PAGE] arc gauges. */
    data class Controls(val switches: List<SwitchItem>, val knobs: List<KnobItem>) : HomePage

    /**
     * The active flight mode and the configured logical switches.
     *
     * @property flightModesUsed the model has flight modes
     * @property logicSwitches the first [LOGIC_CHIPS_PER_PAGE] configured logical switches
     * @property hiddenLogicSwitches how many further ones do not fit
     */
    data class Modes(
        val flightModesUsed: Boolean,
        val logicSwitches: List<LogicSwitchItem>,
        val hiddenLogicSwitches: Int,
    ) : HomePage
}

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
    val pages: List<HomePage> = emptyList(),
    val pageIndex: Int = 0,
) {
    /** The channels of the page shown. */
    val visibleChannels: List<ChannelItem>
        get() = channels.drop(channelPage * CHANNELS_PER_PAGE).take(CHANNELS_PER_PAGE)
}

/** Builds the state of the home screen. Pure: the same inputs always give the same state. */
internal fun buildHomeState(model: ModelEntry?, extras: ModelExtras, config: WorkingConfig): HomeUiState {
    val pages = planPages(config, extras)
    return HomeUiState(
        modelName = model?.name.orEmpty(),
        picturePath = model?.picturePath,
        pictureVersion = model?.pictureVersion ?: 0,
        modelType = extras.modelType,
        centerCard = extras.home.centerCard,
        pinnedWidgets = extras.telemetry.pinnedWidgets,
        stickMode = config.system?.stickMode ?: StickMode.MODE_2,
        channels = List(CHANNEL_COUNT) { ChannelItem(it, emphasized = (config.channel(it)?.mixCount ?: 0) > 0) },
        channelPage = extras.home.channelPage.coerceIn(0, CHANNEL_PAGES - 1),
        timers = timerItems(config),
        pages = pages,
        pageIndex = extras.home.inputPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
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
 * Plans the pages of the input panel (SPEC section 2).
 *
 * Enabled switches (SA-SP) are listed [SWITCHES_PER_PAGE] per page and enabled knobs
 * [KNOBS_PER_PAGE] per page, sliders first, then pots, then counters; page n shows the n-th chunk
 * of both, so the stock layout (SA-SF above S1 and S2) is page 1. A final page shows the flight
 * mode and the logical switches when the model uses either. At most [MAX_INPUT_PAGES] pages.
 */
internal fun planPages(config: WorkingConfig, extras: ModelExtras): List<HomePage> {
    val switchIds: List<SourceId>
    val knobIds: List<SourceId>
    if (config.sources.isEmpty()) {
        switchIds = FallbackSwitches
        knobIds = FallbackKnobs
    } else {
        val enabled = config.sources.filter { it.enabled }.map { it.id }
        switchIds = enabled.filter { it.kind == SourceKind.SWITCH }
        knobIds = enabled.filter { it.kind == SourceKind.SLIDER } +
            enabled.filter { it.kind == SourceKind.POT } +
            enabled.filter { it.kind == SourceKind.COUNTER }
    }
    val switches = switchIds.map { SwitchItem(it, sourceLabel(config, it)) }.chunked(SWITCHES_PER_PAGE)
    val knobs = knobIds.map { KnobItem(it, sourceLabel(config, it), knobRange(it)) }.chunked(KNOBS_PER_PAGE)

    val pages = ArrayList<HomePage>()
    for (i in 0 until maxOf(switches.size, knobs.size)) {
        pages += HomePage.Controls(switches.getOrElse(i) { emptyList() }, knobs.getOrElse(i) { emptyList() })
    }

    val logic = extras.logicalSwitches.filter { it.isUsed }.sortedBy { it.index }
    if (extras.flightModes.isActive || logic.isNotEmpty()) {
        pages += HomePage.Modes(
            flightModesUsed = extras.flightModes.isActive,
            logicSwitches = logic.take(LOGIC_CHIPS_PER_PAGE).map { LogicSwitchItem(it.index, it.name.takeIf { n -> n.length in 1..MAX_LABEL_LENGTH } ?: it.label) },
            hiddenLogicSwitches = (logic.size - LOGIC_CHIPS_PER_PAGE).coerceAtLeast(0),
        )
    }
    // The flight-mode page is the last one; keep it when the controls have to be cut.
    if (pages.size > MAX_INPUT_PAGES) {
        val last = pages.last()
        val keep = if (last is HomePage.Modes) MAX_INPUT_PAGES - 1 else MAX_INPUT_PAGES
        val cut = pages.take(keep).toMutableList()
        if (last is HomePage.Modes) cut += last
        return cut
    }
    return pages
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
