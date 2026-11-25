package org.openax12.core.audio

import android.app.Activity
import android.app.Instrumentation
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.openax12.core.model.AudioCategory
import org.openax12.core.model.BeepPattern
import org.openax12.core.model.HapticPattern
import org.openax12.core.model.SensorUnit
import org.openax12.core.model.SpeechPriority
import org.openax12.core.model.VarioTone
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device smoke check of the real audio engine (text-to-speech, tone thread, media players,
 * focus, vibrator). It needs no test library: it is the instrumentation itself.
 *
 * Build and run (emulator shown; the same works on the AX12 over adb):
 * ```
 * scripts/gw :core:audio:assembleDebugAndroidTest
 * adb -s emulator-5554 install -r -t core/audio/build/outputs/apk/androidTest/debug/audio-debug-androidTest.apk
 * adb -s emulator-5554 shell am instrument -w org.openax12.core.audio.test/org.openax12.core.audio.SmokeInstrumentation
 * ```
 * Every check is reported as `name=ok` or `name=FAILED …` in the instrumentation result; the run
 * is quiet (10 % volume) and takes about 25 seconds. `-e volume 60` makes it audible.
 */
class SmokeInstrumentation : Instrumentation() {

    private val results = Bundle()
    private var failures = 0
    private var volume = 10

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        volume = arguments?.getString("volume")?.toIntOrNull()?.coerceIn(0, 100) ?: 10
        start()
    }

    override fun onStart() {
        try {
            runChecks()
        } catch (t: Throwable) {
            failures++
            results.putString("crash", Log.getStackTraceString(t))
        }
        results.putString("summary", if (failures == 0) "ALL OK" else "$failures FAILED")
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        results.putString(name, (if (ok) "ok" else "FAILED") + (if (detail.isEmpty()) "" else " ($detail)"))
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(20)
        }
        return condition()
    }

    private fun audioThreads(): List<String> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("ax12-") }.map { it.name }

    /** The tone output on its own: does the low-latency track really open and take samples? */
    private fun checkToneOutput() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val player = TonePlayer(targetContext, attributes)
        player.start()
        player.setGain(AudioCategory.BEEP, VolumeModel.taper(volume / 100f))
        player.setGain(AudioCategory.VARIO, VolumeModel.taper(volume / 100f))
        player.play(AudioCategory.BEEP, BeepPatterns.steps(BeepPattern.TIMER_EXPIRED))
        player.setVario(VarioTone(false, 600f, 0, 0))
        SystemClock.sleep(1_000)
        val frames = player.framesWritten
        check("toneOutputOpens", frames > 0, "${player.outputDescription}; $frames frames in 1 s")
        results.putString("toneOutput", player.outputDescription)
        results.putString("toneUnderruns", player.underruns.toString())
        player.stopAll()
        SystemClock.sleep(3_000)
        val paused = player.framesWritten
        SystemClock.sleep(300)
        check("toneOutputPausesWhenSilent", player.framesWritten == paused)
        player.play(AudioCategory.BEEP, BeepPatterns.steps(BeepPattern.SHORT))
        check("toneOutputResumes", waitFor(1_000) { player.framesWritten > paused })
        player.shutdown()
        check("toneThreadEnds", waitFor(3_000) { audioThreads().isEmpty() }, audioThreads().toString())
    }

    private fun runChecks() {
        checkToneOutput()

        val sounds = File(targetContext.cacheDir, "smoke-sounds").apply {
            deleteRecursively()
            mkdirs()
        }
        writeWav(File(sounds, "chirp.wav"), seconds = 0.6)
        File(sounds, "broken.wav").writeText("this is not audio")

        val scope = CoroutineScope(SupervisorJob())
        val engine = createAudioEngine(targetContext, scope) { sounds }

        // Before start: configuration is remembered, playback ignored.
        engine.setMasterVolume(volume)
        engine.setVolume(AudioCategory.MUSIC, 60)
        engine.setSpeech("en-US", 1.2f)
        engine.beep(BeepPattern.SHORT)
        check("playSoundBeforeStart", !engine.playSound("chirp.wav"))
        check("phraseBeforeStart", engine.spokenValue(12.44, SensorUnit.VOLT, 1) == "12.4 volts")

        engine.start()
        engine.start()
        check("threadsRunning", waitFor(2_000) { audioThreads().toSet() == setOf("ax12-audio", "ax12-tone") }, audioThreads().toString())

        // Text-to-speech detection.
        val settled = waitFor(15_000) { engine.ttsStatus.value != TtsStatus.Initializing }
        val status = engine.ttsStatus.value
        check("ttsStatusSettles", settled, status.toString())
        results.putString("ttsStatus", status.toString())
        results.putString("ttsLanguages", runBlocking { engine.availableLanguages() }.take(12).toString())

        // Tone thread: beeps, tone sequence, vario.
        engine.beep(BeepPattern.DOUBLE)
        SystemClock.sleep(350)
        engine.beep(BeepPattern.TRIM_CENTER)
        engine.tone(880, 120)
        SystemClock.sleep(250)
        for (step in 0 until 20) {
            engine.setVarioTone(VarioTone(false, 700f + 40f * step, 150 - 5 * step, 150 - 5 * step))
            SystemClock.sleep(40)
        }
        engine.setVarioTone(VarioTone(false, 420f, 250, 0))
        SystemClock.sleep(400)
        engine.setVarioTone(VarioTone.SILENT)
        SystemClock.sleep(100)
        engine.stopVario()
        check("toneThreadAlive", "ax12-tone" in audioThreads())

        // Sound files.
        val listed = runBlocking { engine.listSoundFiles() }
        check("listSoundFiles", listed.map { it.name } == listOf("broken.wav", "chirp.wav"), listed.toString())
        check("soundDuration", (listed.firstOrNull { it.name == "chirp.wav" }?.durationMs ?: 0) in 400..800, listed.toString())
        check("missingSound", !engine.playSound("nope.wav"))
        check("playSound", engine.playSound("chirp"))
        check("soundStarts", waitFor(3_000) { engine.isSpeaking.value })
        check("soundEnds", waitFor(5_000) { !engine.isSpeaking.value })
        check("brokenSoundAccepted", engine.playSound("broken.wav"))
        check("brokenSoundSkipped", waitFor(8_000) { !engine.isSpeaking.value && !engine.playSound("broken.wav") })

        // Music with ducking under speech / sounds.
        check("startMusic", engine.startMusic("chirp.wav", loop = true))
        check("musicPlays", waitFor(3_000) { engine.musicPlaying.value })
        if (status is TtsStatus.Ready) {
            val before = engine.lastSpoken.value
            engine.speak("Smoke test", SpeechPriority.NORMAL)
            engine.speakValue(12.4, SensorUnit.VOLT, 1, name = "Battery")
            engine.speak("Alarm", SpeechPriority.ALARM, AudioCategory.ALARM)
            check("speechStarts", waitFor(5_000) { engine.lastSpoken.value != before }, engine.lastSpoken.value.toString())
            check("alarmWasSpoken", waitFor(10_000) { engine.lastSpoken.value?.text == "Alarm" }, engine.lastSpoken.value.toString())
            check("speechEnds", waitFor(15_000) { !engine.isSpeaking.value })
        } else {
            engine.speak("Nobody can say this")
            SystemClock.sleep(300)
            check("speechDroppedWithoutEngine", !engine.isSpeaking.value)
        }
        engine.playSound("chirp.wav", SpeechPriority.HIGH)
        check("soundOverMusic", waitFor(3_000) { engine.isSpeaking.value } && waitFor(5_000) { !engine.isSpeaking.value })
        check("musicStillPlays", engine.musicPlaying.value)
        engine.pauseMusic()
        check("musicPauses", waitFor(2_000) { !engine.musicPlaying.value })
        check("musicResumes", engine.startMusic("chirp.wav") && waitFor(2_000) { engine.musicPlaying.value })
        engine.stopMusic()
        check("musicStops", waitFor(2_000) { !engine.musicPlaying.value })

        // Volume changes from any thread, haptics, stopAll.
        val hammer = List(4) { index ->
            Thread {
                repeat(200) { tick ->
                    engine.setVolumeOverride((tick + index) % (volume + 1))
                    engine.beep(BeepPattern.TRIM_STEP)
                    engine.speak("chatter $index", SpeechPriority.LOW)
                }
            }.also(Thread::start)
        }
        hammer.forEach(Thread::join)
        engine.setVolumeOverride(null)
        engine.haptic(HapticPattern.DOUBLE)
        engine.setHapticsEnabled(false)
        engine.haptic(HapticPattern.LONG)
        engine.setHapticsEnabled(true)
        engine.startMusic("chirp.wav")
        engine.playSound("chirp.wav")
        engine.setVarioTone(VarioTone(false, 500f, 0, 0))
        SystemClock.sleep(200)
        engine.stopAll()
        check("stopAll", waitFor(3_000) { !engine.isSpeaking.value && !engine.musicPlaying.value })
        check("stillRunningAfterHammering", audioThreads().toSet() == setOf("ax12-audio", "ax12-tone"), audioThreads().toString())

        // Clean shutdown, restart, shutdown through the scope.
        engine.shutdown()
        engine.shutdown()
        check("threadsEndOnShutdown", waitFor(5_000) { audioThreads().isEmpty() }, audioThreads().toString())
        check("statusResetOnShutdown", engine.ttsStatus.value == TtsStatus.Initializing)
        check("playSoundAfterShutdown", !engine.playSound("chirp.wav"))
        engine.beep(BeepPattern.SHORT)

        engine.start()
        check("restarts", waitFor(2_000) { audioThreads().size == 2 }, audioThreads().toString())
        check("ttsStatusSettlesAgain", waitFor(15_000) { engine.ttsStatus.value != TtsStatus.Initializing }, engine.ttsStatus.value.toString())
        engine.beep(BeepPattern.CONFIRM)
        SystemClock.sleep(300)
        scope.cancel()
        check("threadsEndWithScope", waitFor(5_000) { audioThreads().isEmpty() }, audioThreads().toString())

        sounds.deleteRecursively()
    }

    /** A quiet 16-bit mono sine sweep. */
    private fun writeWav(file: File, seconds: Double) {
        val rate = 22_050
        val count = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(count * 2)
        var phase = 0.0
        for (i in 0 until count) {
            val fade = minOf(1.0, i / 400.0, (count - i) / 400.0)
            phase += 2 * PI * (500.0 + 600.0 * i / count) / rate
            data.putShort((sin(phase) * fade * 0.3 * Short.MAX_VALUE).toInt().toShort())
        }
        file.writeBytes(data.array())
    }
}
