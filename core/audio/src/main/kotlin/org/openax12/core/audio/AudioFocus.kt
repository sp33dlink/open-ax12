package org.openax12.core.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/**
 * Audio focus for speech / sounds (transient, other apps duck) and background music (long-term).
 * Beeps and the vario tone are mixed over everything without asking for focus.
 *
 * Confined to the audio control thread; focus changes are delivered to [handler].
 */
internal class FocusController(
    private val audioManager: AudioManager?,
    private val handler: Handler,
    private val attributes: AudioAttributes,
    private val callbacks: Callbacks,
) {

    /** Callbacks on the control thread. */
    interface Callbacks {
        /**
         * Another app took the focus from the music.
         *
         * @param transient it will come back ([onMusicFocusRegained])
         */
        fun onMusicFocusLost(transient: Boolean)

        fun onMusicFocusRegained()
    }

    private enum class Level { NONE, TRANSIENT, MUSIC }

    private var held = Level.NONE
    private var request: AudioFocusRequest? = null

    /**
     * Requests or abandons focus to match what is audible.
     *
     * @param music background music is playing (or waiting for the focus to return)
     * @param speech speech or a sound file is playing or queued
     */
    fun update(music: Boolean, speech: Boolean) {
        val wanted = when {
            music -> Level.MUSIC
            speech -> Level.TRANSIENT
            else -> Level.NONE
        }
        if (wanted == held) return
        abandon()
        if (wanted == Level.NONE) return
        val manager = audioManager ?: return

        val gain = if (wanted == Level.MUSIC) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        lateinit var created: AudioFocusRequest
        created = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(created, wanted, change) }, handler)
            .build()
        try {
            // A refusal (phone call on a phone) is not a reason to stay silent on a transmitter:
            // the request is remembered either way so that it is not repeated for every utterance.
            manager.requestAudioFocus(created)
        } catch (e: RuntimeException) {
            return
        }
        request = created
        held = wanted
    }

    /** Gives the focus back. */
    fun abandon() {
        val active = request
        request = null
        held = Level.NONE
        if (active != null) {
            try {
                audioManager?.abandonAudioFocusRequest(active)
            } catch (e: RuntimeException) {
                // The audio service is gone; so is the focus.
            }
        }
    }

    private fun onFocusChange(source: AudioFocusRequest, level: Level, change: Int) {
        if (request !== source) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent: the request is void, a later update() asks again.
                request = null
                held = Level.NONE
                if (level == Level.MUSIC) callbacks.onMusicFocusLost(transient = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (level == Level.MUSIC) callbacks.onMusicFocusLost(transient = true)
            AudioManager.AUDIOFOCUS_GAIN ->
                if (level == Level.MUSIC) callbacks.onMusicFocusRegained()
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks our players by itself (API 26+).
            else -> Unit
        }
    }
}
