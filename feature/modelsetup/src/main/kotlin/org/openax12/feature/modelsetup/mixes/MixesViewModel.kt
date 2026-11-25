package org.openax12.feature.modelsetup.mixes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openax12.core.model.CHANNEL_COUNT
import org.openax12.core.model.MixKey
import org.openax12.core.model.MixLine
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.SourceId
import org.openax12.core.model.WorkingConfig
import org.openax12.core.radio.Radio
import org.openax12.core.radio.findMix
import org.openax12.core.runtime.ConfigSession
import org.openax12.feature.modelsetup.R
import org.openax12.feature.modelsetup.common.SetupViewModel
import org.openax12.feature.modelsetup.logic.ManagedKind

/**
 * One mix line for the UI.
 *
 * @property managed who generated the line, null for a user line
 * @property modified a generated line that was edited by hand since it was generated
 */
internal data class MixLineUi(
    val line: MixLine,
    val sourceName: String,
    val managed: ManagedKind?,
    val modified: Boolean,
)

/** One channel row of the Mixes tab. */
internal data class ChannelMixesUi(val channel: Int, val lines: List<MixLineUi>)

/**
 * State of the Mixes tab.
 *
 * @property config the working configuration (names, curves, channel settings for the explain line)
 * @property expanded the expanded channel, if any
 * @property acknowledged generated lines the user already accepted to edit by hand (no second warning)
 */
internal data class MixesState(
    val ready: Boolean = false,
    val config: WorkingConfig = WorkingConfig.EMPTY,
    val channels: List<ChannelMixesUi> = emptyList(),
    val expanded: Int? = null,
    val acknowledged: Set<MixKey> = emptySet(),
)

/** Builds the rows of the Mixes tab from the configuration and the managed bookkeeping of the model. */
internal fun mixChannels(config: WorkingConfig, extras: ModelExtras): List<ChannelMixesUi> =
    config.channels.sortedBy { it.channel }.map { channel ->
        ChannelMixesUi(
            channel = channel.channel,
            lines = config.mixesOf(channel.channel).map { line ->
                val record = extras.managed.mix(line.channel, line.source.index)
                MixLineUi(
                    line = line,
                    sourceName = config.sourceName(line.source),
                    managed = ManagedKind.of(record?.generator),
                    modified = record != null && extras.managed.isModified(record, config),
                )
            },
        )
    }

/** View model of the Mixes tab. */
internal class MixesViewModel(
    session: ConfigSession,
    extras: StateFlow<ModelExtras>,
    scopeOverride: CoroutineScope? = null,
) : SetupViewModel(session, scopeOverride) {

    private val expanded = MutableStateFlow<Int?>(null)
    private val acknowledged = MutableStateFlow<Set<MixKey>>(emptySet())

    /** State for the screen. */
    val state: StateFlow<MixesState> = combine(session.workingConfig, extras, expanded, acknowledged) { config, ex, open, ack ->
        MixesState(
            ready = !config.isEmpty,
            config = config,
            channels = mixChannels(config, ex),
            expanded = open,
            acknowledged = ack,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), MixesState())

    /** Expands [channel], or collapses it when it is open. */
    fun toggle(channel: Int) {
        expanded.update { if (it == channel) null else channel }
    }

    /** The user accepted to edit the generated line [key] by hand. */
    fun acknowledge(key: MixKey) {
        acknowledged.update { it + key }
    }

    /** Rewrites [line] (found again by its `(channel, source)` key) with [transform]. */
    fun update(line: MixLine, transform: (MixLine) -> MixLine) {
        edit { radio ->
            val current = radio.findMix(line.channel, line.source) ?: return@edit false
            radio.setMix(transform(current))
        }
    }

    /**
     * Changes the input of [line] to [source] in place (`rcSetMixCfgData` overlays `srcIdx`,
     * assumption m1). Fails if [source] is already used on the channel or the radio keeps the old one.
     */
    fun setSource(line: MixLine, source: SourceId) {
        if (!source.isValid || source == line.source) return
        edit(failure = R.string.mixes_source_failed) { radio ->
            changeSource(radio, line, source)
        }
    }

    /** Adds a mix of [source] to [channel] with the SDK's defaults. */
    fun add(channel: Int, source: SourceId) {
        if (channel !in 0 until CHANNEL_COUNT || !source.isValid) return
        edit(failure = R.string.mixes_add_failed) { radio -> radio.addMix(channel, source) != null }
    }

    /** Deletes [line]; a generated line also loses its managed record. */
    fun delete(line: MixLine) {
        scope.launch {
            val deleted = session.edit { radio -> radio.deleteMix(line.channel, line.source) != null }
            if (!deleted) {
                message(R.string.mixes_delete_failed)
                return@launch
            }
            session.updateExtras { it.copy(managed = it.managed.withoutMix(line.channel, line.source.index)) }
        }
    }

    private suspend fun changeSource(radio: Radio, line: MixLine, source: SourceId): Boolean {
        val current = radio.findMix(line.channel, line.source) ?: return false
        if (radio.findMix(line.channel, source) != null) return false
        if (!radio.setMix(current.copy(source = source))) return false
        return radio.findMix(line.channel, source) != null
    }
}
