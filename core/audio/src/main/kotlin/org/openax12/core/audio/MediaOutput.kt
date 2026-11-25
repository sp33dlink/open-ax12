package org.openax12.core.audio

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import java.io.File
import kotlin.math.abs

/** Duration of an audio file in milliseconds, 0 if unknown. Blocking; not for the main thread. */
internal fun probeDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: RuntimeException) {
        0L
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Declared to throw IOException on newer API levels; nothing to do about it.
        }
    }
}

/**
 * Plays one queued sound file at a time. Confined to the audio control thread: the players are
 * created there, so their callbacks arrive there as well.
 *
 * @param onFinished called once per started file unless it was cancelled; `ok = false` means the
 *   file could not be decoded
 */
internal class SoundPlayback(
    private val handler: Handler,
    private val attributes: AudioAttributes,
    private val onFinished: (id: Long, file: File, ok: Boolean) -> Unit,
) {

    private var player: MediaPlayer? = null
    private var currentId = 0L
    private var currentFile: File? = null
    private val watchdog = Any()

    /**
     * Starts [file]. Decoding is prepared asynchronously.
     *
     * @return false if the player could not even be set up; [onFinished] is then not called
     */
    fun begin(id: Long, file: File, volume: Float): Boolean {
        release()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(attributes)
            created.setDataSource(file.path)
            created.setVolume(volume, volume)
            created.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                try {
                    prepared.start()
                    // A decoder that never reports completion must not block the queue.
                    val duration = prepared.duration.coerceAtLeast(0)
                    armWatchdog(prepared, duration + FINISH_MARGIN_MS, decodable = true)
                } catch (e: IllegalStateException) {
                    finish(prepared, ok = false)
                }
            }
            created.setOnCompletionListener { finish(it, ok = true) }
            created.setOnErrorListener { failed, _, _ ->
                finish(failed, ok = false)
                true
            }
            created.prepareAsync()
        } catch (e: Exception) {
            // IOException, IllegalArgumentException, SecurityException, IllegalStateException.
            releaseQuietly(created)
            return false
        }
        player = created
        currentId = id
        currentFile = file
        armWatchdog(created, PREPARE_TIMEOUT_MS, decodable = false)
        return true
    }

    /** Stops without calling [onFinished]. */
    fun cancel() = release()

    /** Changes the volume of the playing file. */
    fun setVolume(volume: Float) {
        try {
            player?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
            // The player is in its error state; its error callback ends it.
        }
    }

    /** Releases the player. */
    fun release() {
        handler.removeCallbacksAndMessages(watchdog)
        val active = player ?: return
        player = null
        currentFile = null
        releaseQuietly(active)
    }

    /**
     * Ends [target] after [delayMs] if it has not ended by itself.
     *
     * @param decodable what to report then: running into the watchdog after playback started is
     *   a hang, not a bad file
     */
    private fun armWatchdog(target: MediaPlayer, delayMs: Long, decodable: Boolean) {
        handler.removeCallbacksAndMessages(watchdog)
        handler.postDelayed({ finish(target, ok = decodable) }, watchdog, delayMs)
    }

    private fun finish(target: MediaPlayer, ok: Boolean) {
        if (player !== target) return
        val id = currentId
        val file = currentFile
        release()
        if (file != null) onFinished(id, file, ok)
    }

    private companion object {
        const val PREPARE_TIMEOUT_MS = 5_000L
        const val FINISH_MARGIN_MS = 3_000L
    }
}

/**
 * Background music: one looping (or one-shot) track with pause / resume and a ramped volume for
 * ducking. Confined to the audio control thread.
 */
internal class MusicPlayback(
    private val handler: Handler,
    private val attributes: AudioAttributes,
    private val listener: Listener,
) {

    /** Callbacks on the control thread, never from inside a call into this class. */
    interface Listener {
        /** [isPlaying] or [wantsFocus] changed. */
        fun onMusicStateChanged()

        /** [file] could not be decoded. */
        fun onMusicFailed(file: File)
    }

    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlaying = false
    private var pausedForFocus = false
    private var baseVolume = 1f
    private var ducked = false
    private var appliedVolume = 0f
    private val ramp = Any()

    /** The loaded track (playing or paused); null if none. */
    var file: File? = null
        private set

    /** True while music plays or is about to (not paused). */
    val isPlaying: Boolean get() = player != null && wantPlaying

    /** True while the music needs audio focus: playing, or paused only until focus returns. */
    val wantsFocus: Boolean get() = player != null && (wantPlaying || pausedForFocus)

    /** True if the track is loaded but paused. */
    val isPaused: Boolean get() = player != null && !wantPlaying

    /**
     * Plays [track]. The same track resumes if it is paused and keeps playing if it plays;
     * another track replaces the current one.
     *
     * @return false if the player could not be set up
     */
    fun start(track: File, loop: Boolean, volume: Float): Boolean {
        baseVolume = volume
        val active = player
        if (active != null && file == track) {
            try {
                active.isLooping = loop
            } catch (e: IllegalStateException) {
                // Falls through to a fresh player below.
                release()
                return start(track, loop, volume)
            }
            resume()
            startRamp()
            return true
        }
        release()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(attributes)
            created.setDataSource(track.path)
            created.isLooping = loop
            appliedVolume = targetVolume()
            created.setVolume(appliedVolume, appliedVolume)
            created.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                if (wantPlaying) startQuietly(ready)
            }
            created.setOnCompletionListener { ended ->
                // Only reached without looping.
                if (player === ended) {
                    release()
                    listener.onMusicStateChanged()
                }
            }
            created.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    val broken = file
                    release()
                    if (broken != null) listener.onMusicFailed(broken)
                    listener.onMusicStateChanged()
                }
                true
            }
            created.prepareAsync()
        } catch (e: Exception) {
            releaseQuietly(created)
            return false
        }
        player = created
        file = track
        prepared = false
        wantPlaying = true
        pausedForFocus = false
        return true
    }

    /**
     * Pauses, keeping the position.
     *
     * @param forFocus the pause is temporary (another app took the focus for a moment)
     */
    fun pause(forFocus: Boolean = false) {
        val active = player ?: return
        if (!wantPlaying) {
            // A user pause overrides a pending automatic resume.
            if (!forFocus) pausedForFocus = false
            return
        }
        wantPlaying = false
        pausedForFocus = forFocus
        if (prepared) {
            try {
                active.pause()
            } catch (e: IllegalStateException) {
                release()
            }
        }
    }

    /** Continues a paused track. */
    fun resume() {
        val active = player ?: return
        pausedForFocus = false
        if (wantPlaying) return
        wantPlaying = true
        if (prepared) startQuietly(active)
    }

    /** Continues only if the pause was a temporary focus loss. */
    fun resumeAfterFocusLoss() {
        if (pausedForFocus) resume()
    }

    /** Stops and forgets the track. */
    fun stop() = release()

    /** Sets the unducked volume (linear gain). */
    fun setVolume(volume: Float) {
        baseVolume = volume
        startRamp()
    }

    /** Lowers the music while speech or a sound plays. */
    fun setDucked(duck: Boolean) {
        if (ducked == duck) return
        ducked = duck
        startRamp()
    }

    private fun targetVolume(): Float = baseVolume * (if (ducked) DUCK_FACTOR else 1f)

    private fun startRamp() {
        handler.removeCallbacksAndMessages(ramp)
        if (player == null) return
        stepRamp()
    }

    private fun stepRamp() {
        val active = player ?: return
        val target = targetVolume()
        val delta = target - appliedVolume
        val done = abs(delta) <= RAMP_STEP
        appliedVolume = if (done) target else appliedVolume + (if (delta > 0f) RAMP_STEP else -RAMP_STEP)
        try {
            active.setVolume(appliedVolume, appliedVolume)
        } catch (e: IllegalStateException) {
            return
        }
        if (!done) handler.postDelayed(::stepRamp, ramp, RAMP_INTERVAL_MS)
    }

    private fun startQuietly(target: MediaPlayer) {
        try {
            target.start()
        } catch (e: IllegalStateException) {
            val broken = file
            release()
            handler.post {
                if (broken != null) listener.onMusicFailed(broken)
                listener.onMusicStateChanged()
            }
        }
    }

    private fun release() {
        handler.removeCallbacksAndMessages(ramp)
        val active = player
        player = null
        file = null
        prepared = false
        wantPlaying = false
        pausedForFocus = false
        if (active != null) releaseQuietly(active)
    }

    private companion object {
        /** Music level under speech. */
        const val DUCK_FACTOR = 0.25f

        /** Volume change per ramp step: the full range takes a quarter of a second. */
        const val RAMP_STEP = 0.08f
        const val RAMP_INTERVAL_MS = 20L
    }
}

private fun releaseQuietly(player: MediaPlayer) {
    try {
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.release()
    } catch (e: RuntimeException) {
        // Already released or the media server died.
    }
}
