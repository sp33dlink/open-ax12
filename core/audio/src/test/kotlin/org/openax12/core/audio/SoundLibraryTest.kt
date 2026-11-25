package org.openax12.core.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SoundLibraryTest {

    private lateinit var parent: File
    private lateinit var sounds: File
    private var directory: File? = null
    private val library = SoundLibrary { directory }

    private fun file(path: String, bytes: Int = 16): File {
        val target = File(sounds, path)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes) { it.toByte() })
        return target
    }

    @Before
    fun setUp() {
        parent = Files.createTempDirectory("ax12-sounds").toFile()
        sounds = File(parent, "sounds").apply { mkdirs() }
        directory = sounds
        file("alarm.wav")
        file("Beta.ogg")
        file("sub/gear.mp3")
        file("sub/deeper/flaps.WAV")
        file("notes.txt")
        file("empty.wav", bytes = 0)
        file(".hidden.wav")
        File(parent, "outside.wav").writeBytes(ByteArray(8))
    }

    @After
    fun tearDown() {
        parent.deleteRecursively()
    }

    @Test
    fun `resolves names with and without extension`() {
        assertEquals("alarm.wav", library.resolve("alarm.wav")?.name)
        assertEquals("alarm.wav", library.resolve("alarm")?.name)
        assertEquals("alarm.wav", library.resolve("  alarm.wav ")?.name)
        assertEquals("gear.mp3", library.resolve("sub/gear")?.name)
        assertEquals("gear.mp3", library.resolve("sub\\gear.mp3")?.name)
        assertEquals("flaps.WAV", library.resolve("/sub/deeper/flaps.WAV")?.name)
    }

    @Test
    fun `rejects what is not a playable file inside the folder`() {
        assertNull(library.resolve(""))
        assertNull(library.resolve("missing.wav"))
        assertNull(library.resolve("notes.txt"))
        assertNull(library.resolve("empty.wav"))
        assertNull(library.resolve("sub"))
        assertNull(library.resolve("../outside.wav"))
        assertNull(library.resolve("sub/../../outside.wav"))
        assertNull(library.resolve("./alarm.wav"))
    }

    @Test
    fun `a link out of the folder is not followed`() {
        val link = File(sounds, "link.wav").toPath()
        val created = runCatching { Files.createSymbolicLink(link, File(parent, "outside.wav").toPath()) }.isSuccess
        assumeTrue(created)
        assertNull(library.resolve("link.wav"))
    }

    @Test
    fun `nothing resolves without a folder`() {
        directory = null
        assertNull(library.resolve("alarm.wav"))
        assertTrue(library.list().isEmpty())
        directory = File(parent, "does-not-exist")
        assertNull(library.resolve("alarm.wav"))
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `lists supported files sorted by name with relative names`() {
        val files = library.list { 1234L }
        assertEquals(listOf("alarm.wav", "Beta.ogg", "sub/deeper/flaps.WAV", "sub/gear.mp3"), files.map { it.name })
        assertTrue(files.all { it.sizeBytes == 16L && it.durationMs == 1234L })
        for (entry in files) {
            assertEquals(entry.path, library.resolve(entry.name)?.path)
        }
    }

    @Test
    fun `durations are probed once per file version`() {
        var probes = 0
        val probe = { _: File -> probes++; 500L }
        library.list(probe)
        library.list(probe)
        assertEquals(4, probes)

        val changed = file("alarm.wav", bytes = 99)
        changed.setLastModified(changed.lastModified() + 5_000)
        library.list(probe)
        assertEquals(5, probes)
    }

    @Test
    fun `relative name round trip`() {
        val gear = library.resolve("sub/gear")
        assertNotNull(gear)
        assertEquals("sub/gear.mp3", library.relativeName(gear!!))
    }

    @Test
    fun `remembers undecodable files until they change`() {
        val alarm = library.resolve("alarm.wav")!!
        assertFalse(library.isUndecodable(alarm))
        library.markUndecodable(alarm)
        assertTrue(library.isUndecodable(alarm))
        assertFalse(library.isUndecodable(library.resolve("Beta.ogg")!!))

        file("alarm.wav", bytes = 64)
        assertFalse(library.isUndecodable(library.resolve("alarm.wav")!!))
    }

    @Test
    fun `supported extensions`() {
        assertTrue(SoundLibrary.isSupported("a.wav"))
        assertTrue(SoundLibrary.isSupported("a.b.MP3"))
        assertTrue(SoundLibrary.isSupported("a.ogg"))
        assertFalse(SoundLibrary.isSupported("wav"))
        assertFalse(SoundLibrary.isSupported(".wav"))
        assertFalse(SoundLibrary.isSupported("a.wav."))
        assertFalse(SoundLibrary.isSupported("a.txt"))
    }
}
