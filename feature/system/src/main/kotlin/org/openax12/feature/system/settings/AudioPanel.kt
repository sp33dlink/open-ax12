package org.openax12.feature.system.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.openax12.core.designsystem.AxButton
import org.openax12.core.designsystem.AxDivider
import org.openax12.core.designsystem.AxDropdown
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxSettingRow
import org.openax12.core.designsystem.AxSlider
import org.openax12.core.designsystem.AxSwitch
import org.openax12.core.designsystem.AxTheme
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.HapticPattern
import org.openax12.feature.system.R
import org.openax12.feature.system.common.NoticeBox
import org.openax12.feature.system.common.NoticeKind
import org.openax12.feature.system.common.PanelColumn
import org.openax12.feature.system.common.SettingsGroup
import org.openax12.feature.system.common.TtsNotice
import kotlin.math.roundToInt

@StringRes
internal fun categoryLabel(category: AudioCategory): Int = when (category) {
    AudioCategory.VOICE -> R.string.audio_cat_voice
    AudioCategory.ALARM -> R.string.audio_cat_alarm
    AudioCategory.VARIO -> R.string.audio_cat_vario
    AudioCategory.BEEP -> R.string.audio_cat_beep
    AudioCategory.SOUND -> R.string.audio_cat_sound
    AudioCategory.MUSIC -> R.string.audio_cat_music
}

/** Slowest and fastest speech rate offered. */
internal const val SPEECH_RATE_MIN = 0.5f
internal const val SPEECH_RATE_MAX = 2.0f

/** Audio category: volumes with test buttons, text-to-speech, haptics. */
@Composable
internal fun AudioPanel(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val app = state.app
    val voiceSample = stringResource(R.string.audio_sample_voice)
    val alarmSample = stringResource(R.string.audio_sample_alarm)
    PanelColumn {
        SettingsGroup(stringResource(R.string.group_volume)) {
            VolumeRow(
                label = stringResource(R.string.audio_master),
                percent = app.masterVolume,
                onCommit = {
                    onEvent(masterVolumeUpdate(it))
                    onEvent(SettingsEvent.TestSound(AudioCategory.BEEP, afterSettingChange = true))
                },
            )
            for (category in AudioCategory.entries) {
                AxDivider()
                val speech = if (category == AudioCategory.ALARM) alarmSample else voiceSample
                VolumeRow(
                    label = stringResource(categoryLabel(category)),
                    percent = app.volumeOf(category),
                    onCommit = {
                        onEvent(volumeUpdate(category, it))
                        onEvent(SettingsEvent.TestSound(category, speech, afterSettingChange = true))
                    },
                    testLabel = stringResource(R.string.audio_test),
                    testIcon = if (category == AudioCategory.MUSIC && state.musicPlaying) AxIcons.Stop else AxIcons.Play,
                    onTest = { onEvent(SettingsEvent.TestSound(category, speech)) },
                )
            }
        }

        SettingsGroup(stringResource(R.string.group_speech)) {
            TtsNotice(state.tts, Modifier.padding(4.dp))
            val languageOptions = languageOptions(app.speechLanguage, state.languages)
            val systemDefault = stringResource(R.string.audio_language_default)
            val labels = remember(languageOptions, systemDefault) {
                languageOptions.map { tag -> if (tag.isEmpty()) systemDefault else languageName(tag) }
            }
            AxSettingRow(
                title = stringResource(R.string.audio_language),
                description = stringResource(R.string.audio_language_desc),
                icon = AxIcons.Voice,
            ) {
                AxDropdown(
                    options = labels,
                    selectedIndex = languageOptions.indexOf(app.speechLanguage).coerceAtLeast(0),
                    onSelected = { index -> onEvent(SettingsEvent.Update { it.copy(speechLanguage = languageOptions[index]) }) },
                    title = stringResource(R.string.audio_language),
                    modifier = Modifier.width(240.dp),
                )
            }
            AxDivider()
            SpeechRateRow(rate = app.speechRate, onCommit = { rate -> onEvent(SettingsEvent.Update { it.copy(speechRate = rate) }) })
            AxDivider()
            AxSettingRow(title = stringResource(R.string.audio_speech_test), description = voiceSample) {
                AxButton(
                    text = stringResource(R.string.audio_test),
                    icon = AxIcons.Play,
                    onClick = { onEvent(SettingsEvent.TestSound(AudioCategory.VOICE, voiceSample)) },
                )
            }
        }

        SettingsGroup(stringResource(R.string.group_haptics)) {
            AxSettingRow(
                title = stringResource(R.string.audio_haptics),
                description = stringResource(R.string.audio_haptics_desc),
                icon = AxIcons.Haptic,
            ) {
                AxSwitch(
                    checked = app.haptics,
                    onCheckedChange = { value -> onEvent(SettingsEvent.Update { it.copy(haptics = value) }) },
                )
            }
            AxDivider()
            AxSettingRow(title = stringResource(R.string.audio_haptic_test), enabled = app.haptics) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AxButton(stringResource(R.string.haptic_short), { onEvent(SettingsEvent.TestHaptic(HapticPattern.SHORT)) }, enabled = app.haptics)
                    AxButton(stringResource(R.string.haptic_double), { onEvent(SettingsEvent.TestHaptic(HapticPattern.DOUBLE)) }, enabled = app.haptics)
                    AxButton(stringResource(R.string.haptic_long), { onEvent(SettingsEvent.TestHaptic(HapticPattern.LONG)) }, enabled = app.haptics)
                }
            }
            NoticeBox(stringResource(R.string.audio_haptics_hint), Modifier.padding(4.dp), NoticeKind.Info)
        }
    }
}

@Composable
private fun SpeechRateRow(rate: Float, onCommit: (Float) -> Unit) {
    var drag by remember(rate) { mutableFloatStateOf(rate) }
    AxSettingRow(title = stringResource(R.string.audio_speech_rate), icon = AxIcons.Voice) {
        AxSlider(
            value = drag,
            onValueChange = { drag = it },
            valueRange = SPEECH_RATE_MIN..SPEECH_RATE_MAX,
            steps = 14,
            onValueChangeFinished = { onCommit((drag * 10f).roundToInt() / 10f) },
            modifier = Modifier.width(220.dp),
        )
        Text(
            text = String.format(Locale.US, "%.1f×", drag),
            style = AxTheme.typography.value,
            color = AxTheme.colors.text,
            textAlign = TextAlign.End,
            modifier = Modifier
                .padding(start = 10.dp)
                .width(46.dp),
        )
    }
}

/**
 * The speech languages offered: the empty tag (system default) first, then what the engine can
 * speak; a configured language the engine does not list is kept so the setting stays visible.
 */
internal fun languageOptions(current: String, available: List<String>): List<String> {
    val list = ArrayList<String>()
    list += ""
    available.filter { it.isNotBlank() }.sorted().forEach { if (it !in list) list += it }
    if (current.isNotBlank() && current !in list) list += current
    return list
}

/** Display name of a BCP-47 tag in the current UI language (`en-US` → `English (United States)`). */
internal fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.displayName
    return if (name.isBlank() || name == tag) tag else "$name ($tag)"
}
