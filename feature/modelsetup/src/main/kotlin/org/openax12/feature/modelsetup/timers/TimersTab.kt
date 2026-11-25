package org.openax12.feature.modelsetup.timers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxButtonVariant
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxLiveText
import org.openax12.core.designsystem.AxSectionHeader
import org.openax12.core.designsystem.AxSegmented
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.designsystem.NumberField
import org.openax12.core.designsystem.formatTimer
import org.openax12.core.designsystem.axRaised
import org.openax12.core.designsystem.axSunken
import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CountdownStyle
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.TimerVoiceOptions
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.LocalAppGraph
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.CollectMessages
import org.openax12.feature.modelsetup.common.ConditionBox
import org.openax12.feature.modelsetup.common.DialogRow
import org.openax12.feature.modelsetup.common.LiveFeed
import org.openax12.feature.modelsetup.common.SetupDims
import org.openax12.feature.modelsetup.common.SetupNotReady
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.common.SwitchConditionDialog
import org.openax12.feature.modelsetup.common.collectLive
import org.openax12.feature.modelsetup.common.setupViewModel

/** A timer (`isCounter` false) or counter, by its in-group index 0..2. */
internal data class TimerRef(val index: Int, val isCounter: Boolean = false)

/**
 * State of the Timers tab.
 *
 * @property voice app-side voice options of the three timers (counters have none)
 */
internal data class TimersState(
    val ready: Boolean = false,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val timers: List<TimerConfig> = emptyList(),
    val counters: List<TimerConfig> = emptyList(),
    val voice: List<TimerVoiceOptions> = List(TIMER_COUNT) { TimerVoiceOptions(it) },
    val selected: TimerRef = TimerRef(0),
) {
    /** Configuration of the selected timer / counter, or null while it is unknown. */
    val selectedConfig: TimerConfig?
        get() = (if (selected.isCounter) counters else timers).firstOrNull { it.index == selected.index }
}

/** Largest start value of a timer in seconds (9 h 59 min 59 s). */
internal const val MAX_TIMER_SECONDS: Int = 35_999

/** Largest start value of a counter (its maximum). */
internal const val MAX_COUNTER: Int = 9_999

/** Keeps a counter's maximum at 1 or more (the SDK needs a non-zero value) and values in range. */
internal fun sanitize(config: TimerConfig, isCounter: Boolean): TimerConfig = config.copy(
    startTime = if (isCounter) config.startTime.coerceIn(1, MAX_COUNTER) else config.startTime.coerceIn(0, MAX_TIMER_SECONDS),
    alertIntervalSec = config.alertIntervalSec.coerceIn(0, MAX_ALERT_INTERVAL),
)

private const val MAX_ALERT_INTERVAL = 3_600

/**
 * View model of the Timers tab: the firmware settings go through the editing session, the
 * app-side voice options are staged extras. A timer cannot be started or stopped by hand
 * (CONTRACTS rule 8): it runs while its switch condition holds and can only be reset.
 *
 * @param resetTimer `RuntimeEngine.resetTimer`
 */
internal class TimersViewModel(
    session: ConfigSession,
    extras: StateFlow<ModelExtras>,
    private val resetTimer: suspend (index: Int, isCounter: Boolean) -> Unit,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    private val selected = MutableStateFlow(TimerRef(0))

    /** State for the screen. */
    val state: StateFlow<TimersState> = combine(session.workingConfig, extras, selected) { config, ex, ref ->
        TimersState(
            ready = !config.isEmpty,
            config = config,
            timers = config.timers,
            counters = config.counters,
            voice = List(TIMER_COUNT) { ex.timerVoiceOptions(it) },
            selected = ref,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TimersState())

    /** Shows the settings of [ref]. */
    fun select(ref: TimerRef) {
        selected.value = ref
    }

    /** Changes timer / counter [ref] with [transform] (read-modify-write on a fresh read). */
    fun update(ref: TimerRef, transform: (TimerConfig) -> TimerConfig) {
        val limit = if (ref.isCounter) COUNTER_COUNT else TIMER_COUNT
        if (ref.index !in 0 until limit) return
        edit { radio -> writeTimer(radio, ref, transform) }
    }

    /** Changes the voice options of timer [index]. */
    fun updateVoice(index: Int, transform: (TimerVoiceOptions) -> TimerVoiceOptions) {
        if (index !in 0 until TIMER_COUNT) return
        scope.launch {
            session.updateExtras { it.withTimerVoiceOptions(transform(it.timerVoiceOptions(index)).copy(timer = index)) }
        }
    }

    /** Resets the timer / counter on the radio. */
    fun reset(ref: TimerRef) {
        scope.launch { resetTimer(ref.index, ref.isCounter) }
    }

    private suspend fun writeTimer(radio: Radio, ref: TimerRef, transform: (TimerConfig) -> TimerConfig): Boolean {
        val current = radio.getTimerConfig(ref.index, ref.isCounter) ?: return false
        return radio.setTimerConfig(sanitize(transform(current), ref.isCounter), ref.isCounter)
    }
}

/** The Timers tab. */
@Composable
internal fun TimersTab() {
    val graph = LocalAppGraph.current
    val vm = setupViewModel("modelsetup.timers") {
        TimersViewModel(graph.configSession, graph.models.currentExtras, graph.runtime::resetTimer)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val live = graph.radio.live.collectLive()
    CollectMessages(vm.messages)
    TimersScreen(
        state = state,
        live = live,
        onSelect = vm::select,
        onUpdate = vm::update,
        onUpdateVoice = vm::updateVoice,
        onReset = vm::reset,
    )
}

private val ListWidth = 196.dp
private val ControlWidth = 168.dp

/** Stateless Timers screen: the six timers / counters on the left, the selected one's settings on the right. */
@Composable
internal fun TimersScreen(
    state: TimersState,
    live: LiveFeed,
    onSelect: (TimerRef) -> Unit,
    onUpdate: (TimerRef, (TimerConfig) -> TimerConfig) -> Unit,
    onUpdateVoice: (Int, (TimerVoiceOptions) -> TimerVoiceOptions) -> Unit,
    onReset: (TimerRef) -> Unit,
) {
    if (!state.ready) {
        SetupNotReady()
        return
    }
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(ListWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = SetupDims.Edge, top = 4.dp, bottom = 4.dp),
        ) {
            AxSectionHeader(stringResource(R.string.timers_group_timers))
            for (i in 0 until TIMER_COUNT) {
                TimerListRow(TimerRef(i), state.selected == TimerRef(i), live, onSelect)
            }
            AxSectionHeader(stringResource(R.string.timers_group_counters))
            for (i in 0 until COUNTER_COUNT) {
                TimerListRow(TimerRef(i, isCounter = true), state.selected == TimerRef(i, isCounter = true), live, onSelect)
            }
        }
        val config = state.selectedConfig
        if (config == null) {
            SetupNotReady()
        } else {
            TimerDetails(
                ref = state.selected,
                config = config,
                voice = state.voice.getOrNull(state.selected.index),
                workingConfig = state.config,
                live = live,
                onUpdate = onUpdate,
                onUpdateVoice = onUpdateVoice,
                onReset = onReset,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun timerName(ref: TimerRef): String =
    stringResource(if (ref.isCounter) R.string.timers_counter_name else R.string.timers_timer_name, ref.index + 1)

@Composable
private fun TimerListRow(ref: TimerRef, selected: Boolean, live: LiveFeed, onSelect: (TimerRef) -> Unit) {
    val colors = AxTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(SetupDims.Row)
            .then(if (selected) Modifier.axSunken(cornerRadius = 10.dp, depth = 2.dp) else Modifier.axRaised(cornerRadius = 10.dp, elevation = 3.dp))
            .clickable(role = Role.Tab) { onSelect(ref) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timerName(ref),
            style = AxTheme.typography.bodyStrong,
            color = if (selected) colors.accent else colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        AxLiveText(
            value = {
                val state = if (ref.isCounter) live.value.counter(ref.index) else live.value.timer(ref.index)
                state?.count ?: 0
            },
            format = if (ref.isCounter) Int::toString else ::formatTimer,
            template = if (ref.isCounter) "9999" else "00:00:00",
            style = AxTheme.typography.value,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun TimerDetails(
    ref: TimerRef,
    config: TimerConfig,
    voice: TimerVoiceOptions?,
    workingConfig: WorkingConfig,
    live: LiveFeed,
    onUpdate: (TimerRef, (TimerConfig) -> TimerConfig) -> Unit,
    onUpdateVoice: (Int, (TimerVoiceOptions) -> TimerVoiceOptions) -> Unit,
    onReset: (TimerRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AxTheme.colors
    var editing by remember { mutableStateOf<ConditionTarget?>(null) }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(SetupDims.Field)) {
            Text(
                text = timerName(ref),
                style = AxTheme.typography.title,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            AxButton(
                text = stringResource(R.string.timers_reset),
                onClick = { onReset(ref) },
                icon = AxIcons.Reset,
                height = SetupDims.Field,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                AxSectionHeader(stringResource(R.string.timers_section_firmware))
                DialogRow(stringResource(R.string.timers_switch)) {
                    ConditionBox(config.switch, workingConfig, { editing = ConditionTarget.RUN }, Modifier.width(ControlWidth))
                }
                DialogRow(stringResource(R.string.timers_reset_switch)) {
                    ConditionBox(config.resetSwitch, workingConfig, { editing = ConditionTarget.RESET }, Modifier.width(ControlWidth))
                }
                val countUpText = stringResource(R.string.timers_count_up)
                DialogRow(stringResource(if (ref.isCounter) R.string.timers_max else R.string.timers_start)) {
                    NumberField(
                        value = config.startTime,
                        onValueChange = { v -> onUpdate(ref) { it.copy(startTime = v) } },
                        min = if (ref.isCounter) 1 else 0,
                        max = if (ref.isCounter) MAX_COUNTER else MAX_TIMER_SECONDS,
                        title = stringResource(if (ref.isCounter) R.string.timers_max else R.string.timers_start_title),
                        defaultValue = if (ref.isCounter) 1 else 0,
                        format = if (ref.isCounter) null else { v -> if (v == 0) countUpText else formatMinutesSeconds(v) },
                        height = SetupDims.Field,
                        modifier = Modifier.width(ControlWidth),
                    )
                }
                DialogRow(stringResource(R.string.timers_direction)) {
                    AxSegmented(
                        options = listOf(stringResource(R.string.timers_direction_up), stringResource(R.string.timers_direction_down)),
                        selectedIndex = if (config.countUp) 0 else 1,
                        onSelected = { i -> onUpdate(ref) { it.copy(countUp = i == 0) } },
                        modifier = Modifier.width(ControlWidth),
                    )
                }
                DialogRow(stringResource(R.string.timers_alert)) {
                    val modes = TimerAlertMode.entries
                    AxDropdown(
                        options = modes.map { stringResource(alertLabel(it)) },
                        selectedIndex = modes.indexOf(config.alertMode),
                        onSelected = { i -> onUpdate(ref) { it.copy(alertMode = modes[i]) } },
                        title = stringResource(R.string.timers_alert),
                        height = SetupDims.Field,
                        modifier = Modifier.width(ControlWidth),
                    )
                }
                DialogRow(stringResource(R.string.timers_interval)) {
                    NumberField(
                        value = config.alertIntervalSec,
                        onValueChange = { v -> onUpdate(ref) { it.copy(alertIntervalSec = v) } },
                        min = 0,
                        max = MAX_ALERT_INTERVAL,
                        unit = "s",
                        title = stringResource(R.string.timers_interval),
                        defaultValue = 0,
                        height = SetupDims.Field,
                        modifier = Modifier.width(ControlWidth),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (!ref.isCounter && voice != null) {
                    AxSectionHeader(stringResource(R.string.timers_section_voice))
                    VoiceFields(ref.index, voice, onUpdateVoice)
                }
                AxSectionHeader(stringResource(R.string.timers_section_codes))
                Text(
                    text = stringResource(R.string.timers_codes_hint),
                    style = AxTheme.typography.label,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                CodeField(R.string.timers_code_mode, config.mode) { v -> onUpdate(ref) { it.copy(mode = v) } }
                CodeField(R.string.timers_code_loop, config.loopMode) { v -> onUpdate(ref) { it.copy(loopMode = v) } }
                CodeField(R.string.timers_code_output, config.outputMode) { v -> onUpdate(ref) { it.copy(outputMode = v) } }
            }
        }
    }
    editing?.let { target ->
        val current = if (target == ConditionTarget.RUN) config.switch else config.resetSwitch
        SwitchConditionDialog(
            title = stringResource(if (target == ConditionTarget.RUN) R.string.timers_switch else R.string.timers_reset_switch),
            initial = current,
            config = workingConfig,
            onConfirm = { condition ->
                onUpdate(ref) { if (target == ConditionTarget.RUN) it.copy(switch = condition) else it.copy(resetSwitch = condition) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private enum class ConditionTarget { RUN, RESET }

@Composable
private fun VoiceFields(index: Int, voice: TimerVoiceOptions, onUpdateVoice: (Int, (TimerVoiceOptions) -> TimerVoiceOptions) -> Unit) {
    DialogRow(stringResource(R.string.timers_voice_minute)) {
        AxSwitch(checked = voice.minuteCall, onCheckedChange = { v -> onUpdateVoice(index) { it.copy(minuteCall = v) } })
    }
    DialogRow(stringResource(R.string.timers_voice_countdown)) {
        val styles = CountdownStyle.entries
        AxSegmented(
            options = styles.map { stringResource(countdownLabel(it)) },
            selectedIndex = styles.indexOf(voice.countdown),
            onSelected = { i -> onUpdateVoice(index) { it.copy(countdown = styles[i]) } },
            modifier = Modifier.width(ControlWidth),
        )
    }
    DialogRow(stringResource(R.string.timers_voice_from)) {
        NumberField(
            value = voice.countdownFromSec,
            onValueChange = { v -> onUpdateVoice(index) { it.copy(countdownFromSec = v) } },
            min = 1,
            max = 120,
            unit = "s",
            enabled = voice.countdown != CountdownStyle.NONE,
            title = stringResource(R.string.timers_voice_from),
            defaultValue = 10,
            height = SetupDims.Field,
            modifier = Modifier.width(ControlWidth),
        )
    }
    DialogRow(stringResource(R.string.timers_voice_expired)) {
        AxSwitch(checked = voice.announceExpired, onCheckedChange = { v -> onUpdateVoice(index) { it.copy(announceExpired = v) } })
    }
    DialogRow(stringResource(R.string.timers_voice_haptic)) {
        AxSwitch(checked = voice.haptic, onCheckedChange = { v -> onUpdateVoice(index) { it.copy(haptic = v) } })
    }
}

@Composable
private fun CodeField(labelRes: Int, value: Int, onChange: (Int) -> Unit) {
    DialogRow(stringResource(labelRes)) {
        NumberField(
            value = value,
            onValueChange = onChange,
            min = 0,
            max = MAX_CODE,
            title = stringResource(labelRes),
            height = SetupDims.Field,
            modifier = Modifier.width(ControlWidth),
        )
    }
}

private const val MAX_CODE = 255

/** `mm:ss`, or `h:mm:ss` from one hour. */
internal fun formatMinutesSeconds(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun alertLabel(mode: TimerAlertMode): Int = when (mode) {
    TimerAlertMode.SILENT -> R.string.timers_alert_silent
    TimerAlertMode.BEEPS -> R.string.timers_alert_beeps
    TimerAlertMode.VOICE -> R.string.timers_alert_voice
    TimerAlertMode.HAPTIC -> R.string.timers_alert_haptic
    TimerAlertMode.BEEPS_HAPTIC -> R.string.timers_alert_beeps_haptic
    TimerAlertMode.VOICE_HAPTIC -> R.string.timers_alert_voice_haptic
}

private fun countdownLabel(style: CountdownStyle): Int = when (style) {
    CountdownStyle.NONE -> R.string.timers_countdown_none
    CountdownStyle.BEEPS -> R.string.timers_countdown_beeps
    CountdownStyle.VOICE -> R.string.timers_countdown_voice
}
