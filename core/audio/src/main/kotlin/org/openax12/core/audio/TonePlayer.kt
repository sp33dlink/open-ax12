package org.openax12.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.ToneStep
import org.openax12.core.model.VarioTone
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/**
 * Plays the [ToneMixer] through a low-latency [AudioTrack] on its own thread.
 *
 * Requests are posted from any thread into a lock-free queue and applied by the tone thread
 * before the next block is rendered, so a beep is audible after roughly one track buffer
 * (20–40 ms). The track keeps running (silence) for [LINGER_MS] after the last sound so a burst
 * of trim beeps does not pay the start-up cost each time; after that it is paused and the thread
 * sleeps until the next request.
 */
internal class TonePlayer(context: Context, private val attributes: AudioAttributes) {

    /** A request for the tone thread. */
    private sealed interface Command {
        class Gain(val category: AudioCategory, val gain: Float) : Command
        class Sequence(val category: AudioCategory, val steps: List<ToneStep>) : Command
        class Vario(val tone: VarioTone) : Command
        data object StopVario : Command
        data object StopAll : Command
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val commands = ConcurrentLinkedQueue<Command>()
    private val monitor = Object()

    @Volatile
    private var quit = false
    private var thread: Thread? = null

    /** Frames handed to the audio output so far; stays 0 if the output never opened. Diagnostics. */
    @Volatile
    var framesWritten = 0L
        private set

    /** Buffer underruns reported by the output so far. Diagnostics. */
    @Volatile
    var underruns = 0
        private set

    /** Sample rate, block and buffer size of the open output, empty before it opened. Diagnostics. */
    @Volatile
    var outputDescription = ""
        private set

    /** Starts the tone thread. Call once. */
    @Synchronized
    fun start() {
        if (thread != null || quit) return
        thread = Thread(::run, "ax12-tone").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Sets the linear gain of the voice of [category]. */
    fun setGain(category: AudioCategory, gain: Float) = post(Command.Gain(category, gain))

    /** Plays [steps] now, replacing a running sequence of the same [category]. */
    fun play(category: AudioCategory, steps: List<ToneStep>) = post(Command.Sequence(category, steps.toList()))

    /** Updates the vario tone. */
    fun setVario(tone: VarioTone) = post(Command.Vario(tone))

    /** Fades the vario tone out. */
    fun stopVario() = post(Command.StopVario)

    /** Silences every voice within a few milliseconds. */
    fun stopAll() = post(Command.StopAll)

    /** Fades out, releases the track and ends the thread. Does not block. */
    fun shutdown() {
        quit = true
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun post(command: Command) {
        if (quit) return
        // A stuck consumer (no audio output at all) must not grow the queue without bound.
        if (commands.size > MAX_PENDING_COMMANDS) commands.poll()
        commands.add(command)
        synchronized(monitor) { monitor.notifyAll() }
    }

    // -- Tone thread ----------------------------------------------------------------------------

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: RuntimeException) {
            // Not fatal: the default priority only makes glitches under load more likely.
        }
        val sampleRate = chooseSampleRate()
        val block = chooseBlockFrames(sampleRate)
        val mixer = ToneMixer(sampleRate)
        val buffer = ShortArray(block)
        val lingerFrames = sampleRate.toLong() * LINGER_MS / 1000

        var track: AudioTrack? = null
        var playing = false
        var silentFrames = lingerFrames
        var retryAtMs = 0L
        var seenUnderruns = 0

        try {
            while (!quit) {
                while (true) {
                    val command = commands.poll() ?: break
                    apply(mixer, command)
                }

                if (mixer.isIdle && silentFrames >= lingerFrames) {
                    if (playing) {
                        pause(track)
                        playing = false
                    }
                    synchronized(monitor) {
                        while (!quit && commands.isEmpty()) {
                            try {
                                monitor.wait()
                            } catch (e: InterruptedException) {
                                quit = true
                            }
                        }
                    }
                    continue
                }

                if (track == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= retryAtMs) {
                        track = createTrack(sampleRate, block)
                        if (track == null) retryAtMs = now + RETRY_MS
                    }
                    if (track == null) {
                        // No output: let time pass in the mixer so beeps do not pile up, at
                        // roughly real-time speed.
                        mixer.render(buffer, block)
                        silentFrames = if (mixer.isIdle) silentFrames + block else 0
                        sleepQuietly(block * 1000L / sampleRate)
                        continue
                    }
                }
                if (!playing) {
                    try {
                        track.play()
                        playing = true
                        seenUnderruns = track.underrunCount
                    } catch (e: IllegalStateException) {
                        release(track)
                        track = null
                        retryAtMs = SystemClock.elapsedRealtime() + RETRY_MS
                        continue
                    }
                }

                mixer.render(buffer, block)
                silentFrames = if (mixer.isIdle) silentFrames + block else 0

                val written = track.write(buffer, 0, block, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    // Dead object (audio server restart, route change): rebuild the track.
                    release(track)
                    track = null
                    playing = false
                    retryAtMs = SystemClock.elapsedRealtime() + 50
                    continue
                }

                framesWritten += written

                // Latency tuning: start with a small buffer and grow it only when it underruns.
                val count = track.underrunCount
                if (count > seenUnderruns) {
                    underruns += count - seenUnderruns
                    seenUnderruns = count
                    val capacity = track.bufferCapacityInFrames
                    val current = track.bufferSizeInFrames
                    if (current < capacity) {
                        track.setBufferSizeInFrames(min(capacity, current + block))
                        outputDescription = describe(track, sampleRate, block)
                    }
                }
            }

            // Shutdown: fade out instead of cutting the waveform.
            val active = track
            if (active != null && playing && !mixer.isIdle) {
                mixer.stopAll()
                var frames = sampleRate * SHUTDOWN_FADE_MS / 1000
                while (frames > 0) {
                    val count = min(frames, block)
                    mixer.render(buffer, count)
                    if (active.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING) < 0) break
                    frames -= count
                }
            }
        } catch (e: RuntimeException) {
            // The audio stack misbehaved in a way that was not anticipated. Tones are a
            // convenience (design rule 2): end the thread quietly rather than crash the app.
        } finally {
            quit = true
            commands.clear()
            release(track)
        }
    }

    private fun apply(mixer: ToneMixer, command: Command) {
        when (command) {
            is Command.Gain -> mixer.setGain(command.category, command.gain)
            is Command.Sequence -> mixer.playSequence(command.category, command.steps)
            is Command.Vario -> mixer.setVario(command.tone)
            Command.StopVario -> mixer.stopVario()
            Command.StopAll -> mixer.stopAll()
        }
    }

    /** The output's native rate avoids resampling and is a condition for the fast mixer path. */
    private fun chooseSampleRate(): Int {
        val native = try {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        } catch (e: RuntimeException) {
            null
        }
        val candidates = listOfNotNull(native, 48_000, 44_100)
        for (rate in candidates) {
            if (rate !in 8_000..192_000) continue
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes > 0) return rate
        }
        return 48_000
    }

    /** One hardware burst per write, within sane limits (2–20 ms). */
    private fun chooseBlockFrames(sampleRate: Int): Int {
        val burst = try {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        } catch (e: RuntimeException) {
            null
        } ?: (sampleRate / 200)
        return burst.coerceIn(sampleRate / 500, sampleRate / 50)
    }

    private fun createTrack(sampleRate: Int, block: Int): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        val bytes = max(minBytes, block * BYTES_PER_FRAME * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: RuntimeException) {
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            release(track)
            return null
        }
        try {
            // Use only part of the allocated buffer: latency is what is queued, not what fits.
            val target = max(block * 2, sampleRate * TARGET_BUFFER_MS / 1000)
            track.setBufferSizeInFrames(min(track.bufferCapacityInFrames, target))
        } catch (e: RuntimeException) {
            // Keep the full buffer.
        }
        outputDescription = describe(track, sampleRate, block)
        return track
    }

    private fun describe(track: AudioTrack, sampleRate: Int, block: Int): String = try {
        "$sampleRate Hz, block $block, buffer ${track.bufferSizeInFrames} of ${track.bufferCapacityInFrames} frames"
    } catch (e: RuntimeException) {
        "$sampleRate Hz, block $block"
    }

    private fun pause(track: AudioTrack?) {
        try {
            track?.pause()
            track?.flush()
        } catch (e: IllegalStateException) {
            // Recreated on the next write error.
        }
    }

    private fun release(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
            }
        } catch (e: RuntimeException) {
            // Releasing anyway.
        }
        try {
            track.release()
        } catch (e: RuntimeException) {
            // Nothing left to do.
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(max(1L, ms))
        } catch (e: InterruptedException) {
            quit = true
        }
    }

    private companion object {
        /** How long the track keeps running on silence after the last sound. */
        const val LINGER_MS = 2_000L

        /** Pause before another attempt to open the output. */
        const val RETRY_MS = 1_000L

        /** Initial amount of queued audio. */
        const val TARGET_BUFFER_MS = 25

        /** Fade-out rendered at shutdown. */
        const val SHUTDOWN_FADE_MS = 15

        const val BYTES_PER_FRAME = 2

        const val MAX_PENDING_COMMANDS = 256
    }
}
