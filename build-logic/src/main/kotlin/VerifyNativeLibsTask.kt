import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build unless every prebuilt radio-SDK library listed in `sdk/SDK_CHECKSUMS.sha256`
 * is inside the APK byte-identical (not stripped, not replaced by an NDK copy, not missing) and
 * the JNI bridge is packaged next to them.
 */
abstract class VerifyNativeLibsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    /** Lines of `<sha256>  <file name>`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksums: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = checksums.get().asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (hash, name) = line.split(Regex("\\s+"), limit = 2)
                name to hash.lowercase()
            }
        val artifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No APK metadata in ${apkDirectory.get().asFile}")

        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (artifact in artifacts.elements) {
            val apk = File(artifact.outputFile)
            ZipFile(apk).use { zip ->
                for ((name, hash) in expected) {
                    val entry = zip.getEntry("lib/$ABI/$name")
                    if (entry == null) {
                        problems += "${apk.name}: lib/$ABI/$name is missing"
                        continue
                    }
                    val actual = zip.getInputStream(entry).use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (actual == hash) {
                        lines += "OK  $name  $actual"
                    } else {
                        problems += "${apk.name}: lib/$ABI/$name was modified (sha256 $actual, expected $hash)"
                    }
                }
                if (zip.getEntry("lib/$ABI/$JNI_BRIDGE") == null) {
                    problems += "${apk.name}: lib/$ABI/$JNI_BRIDGE is missing"
                } else {
                    lines += "OK  $JNI_BRIDGE"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Prebuilt native libraries in the APK do not match sdk/SDK_CHECKSUMS.sha256:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val ABI = Ax12Build.ABI
        const val JNI_BRIDGE = "libopenax12jni.so"
    }
}
