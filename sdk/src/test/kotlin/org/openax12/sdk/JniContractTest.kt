package org.openax12.sdk

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JNI bridge binds the Kotlin side by name and signature when `libopenax12jni.so` loads
 * (`JNI_OnLoad`, `RegisterNatives`-less lookup). A mismatch only shows up on the radio, as an
 * `UnsatisfiedLinkError` or a failed load. This test compares the C++ source with the Kotlin
 * declarations and the SDK headers on the host. The library itself is never loaded.
 *
 * Unit tests run in the module directory, so the sources are read relative to it.
 */
class JniContractTest {

    private val cpp = File("src/main/cpp/openax12_jni.cpp").readText()
    private val typesHeader = File("src/main/cpp/include/rmAxSdkTypes.h").readText()
    private val apiHeader = File("src/main/cpp/include/rmAxSdkApi.h").readText()

    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\\n]*"), "")

    /** `kLiveIntSize` -> `LIVE_INT_SIZE`. */
    private fun upperSnake(camel: String): String = camel.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").uppercase()

    private fun constants(of: Class<*>): Map<String, Int> = of.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.isAccessible = true; it.name to it.getInt(null) }

    // ---------------------------------------------------------------------------------------------

    @Test
    fun `message ids are the consecutive enum of the header`() {
        val body = Regex("enum RcMessageId[^{]*\\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL).find(stripComments(typesHeader))!!.groupValues[1]
        val names = Regex("RC_MSGID_(\\w+)").findAll(body).map { it.groupValues[1] }.toList()
        val first = Regex("RC_MSGID_\\w+\\s*=\\s*(0x[0-9A-Fa-f]+)").find(body)!!.groupValues[1].removePrefix("0x").toLong(16)
        assertEquals(0x08000001L, first)
        val expected = names.mapIndexed { index, name -> name to (first + index).toInt() }.toMap()
        assertEquals(27, expected.size)
        assertEquals(expected, constants(SdkMsg::class.java))
    }

    @Test
    fun `init result codes mirror the header enum`() {
        val body = Regex("enum class InitResult\\s*\\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL).find(stripComments(apiHeader))!!.groupValues[1]
        val names = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("Success", "AlreadyInitialized", "ThreadStartFailed", "CommunicationInitFailed"), names)
        val codes = constants(SdkInitCode::class.java)
        assertEquals(0, codes["SUCCESS"])
        assertEquals(1, codes["ALREADY_INITIALIZED"])
        assertEquals(2, codes["THREAD_START_FAILED"])
        assertEquals(3, codes["COMMUNICATION_INIT_FAILED"])
    }

    @Test
    fun `native constants of the bridge equal the Kotlin constants`() {
        val kotlin = constants(NativeLayout::class.java) + constants(RawSizes::class.java) +
            constants(SdkInitCode::class.java).mapKeys { "INIT_" + it.key }
        val declared = Regex("constexpr int (k\\w+) = (-?\\d+);").findAll(cpp).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        var checked = 0
        for ((name, value) in declared) {
            val key = when {
                name.startsWith("kLive") || name.startsWith("kCounts") -> upperSnake(name.removePrefix("k"))
                name.startsWith("kInit") -> "INIT_" + upperSnake(name.removePrefix("kInit"))
                name == "kPreFlightSources" -> "PREFLIGHT_SOURCES"
                name == "kCurvePoints" || name == "kFailsafeChannels" -> upperSnake(name.removePrefix("k"))
                else -> continue
            }
            assertEquals("constant $name", value, kotlin[key] ?: error("no Kotlin counterpart $key for $name"))
            checked++
        }
        assertTrue("only $checked constants compared", checked >= 40)
        // Everything the Kotlin layout declares exists on the native side as well.
        val nativeKeys = declared.keys.map { it }.toSet()
        for (key in constants(NativeLayout::class.java).keys) {
            val camel = "k" + key.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
            assertTrue("native constant $camel missing", camel in nativeKeys)
        }
    }

    @Test
    fun `every native function of SdkNative is defined with the same JNI signature`() {
        val definitions = Regex("JNI_FN\\((\\w+), (\\w+)\\)\\(([^)]*)\\)").findAll(cpp).associate { match ->
            val params = match.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }.drop(2)
            match.groupValues[2] to (match.groupValues[1] to params.map { it.substringBefore(' ') })
        }
        val natives = SdkNative::class.java.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        assertEquals(natives.map { it.name }.toSortedSet(), definitions.keys.toSortedSet())
        for (method in natives) {
            assertFalse("${method.name} must be an instance method of the object", Modifier.isStatic(method.modifiers))
            val (returns, params) = definitions.getValue(method.name)
            assertEquals("return type of ${method.name}", jni(method.returnType), returns)
            assertEquals("parameters of ${method.name}", method.parameterTypes.map { jni(it) }, params)
        }
    }

    @Test
    fun `the message callback target has the signature the bridge looks up`() {
        assertTrue(cpp.contains("\"onNativeMessage\", \"(I[I[D[B[Ljava/lang/String;)V\""))
        val method: Method = SdkNative::class.java.declaredMethods.single { it.name == "onNativeMessage" }
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(
            listOf(Int::class.javaPrimitiveType, IntArray::class.java, DoubleArray::class.java, ByteArray::class.java, Array<String>::class.java),
            method.parameterTypes.toList(),
        )
    }

    @Test
    fun `struct bindings cover exactly the fields of the SDK structs and of the Raw classes`() {
        val code = stripComments(cpp)
        val lists = Regex("#define (RAW_\\w+_FIELDS)\\(X\\)((?:[^\\n]*\\\\\\n)*[^\\n]*)").findAll(code).associate { match ->
            match.groupValues[1] to Regex("X\\((\\w), (\\w+)").findAll(match.groupValues[2]).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        val bindings = Regex("DEFINE_BINDING\\(\\w+, rmaxSdk::(\\w+), \"([^\"]+)\", (\\w+)\\)").findAll(code).toList()
        assertEquals(10, bindings.size)
        for (binding in bindings) {
            val (sdkType, javaName, listName) = binding.destructured
            val fields = lists.getValue(listName)
            val raw = Class.forName(javaName.replace('/', '.'))
            assertTrue("$javaName needs a public no-argument constructor", raw.constructors.any { it.parameterCount == 0 })
            val kotlinFields = raw.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            assertEquals("fields of $javaName", fields.map { it.second }.toSortedSet(), kotlinFields.map { it.name }.toSortedSet())
            for ((kind, name) in fields) {
                val field = raw.getDeclaredField(name)
                assertTrue("$javaName.$name must be public (@JvmField)", Modifier.isPublic(field.modifiers))
                assertEquals("$javaName.$name", fieldType(kind), field.type)
            }
            assertEquals("fields of $sdkType in rmAxSdkTypes.h", headerFields(sdkType).toSortedSet(), fields.map { it.second }.toSortedSet())
        }
    }

    @Test
    fun `no firmware update or app update function is referenced`() {
        val code = stripComments(cpp)
        for (name in listOf("rcTaskStartUpdateFw", "rcGetAppUpdateInfo", "rcTaskGetAppInfoFromWeb", "rcTaskUpdateApp", "rcAbortUpdateApp", "postMessage")) {
            assertFalse("$name must not be called (design rule 4)", code.contains(name))
        }
        val kotlinSources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { stripComments(it.readText()) }
        for (name in listOf("rcTaskStartUpdateFw", "rcTaskUpdateApp", "rcAbortUpdateApp", "postMessage")) {
            assertFalse(name, kotlinSources.contains(name))
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun jni(type: Class<*>): String = when {
        type == Int::class.javaPrimitiveType -> "jint"
        type == Boolean::class.javaPrimitiveType -> "jboolean"
        type == Void.TYPE -> "void"
        type == Double::class.javaPrimitiveType -> "jdouble"
        type == Long::class.javaPrimitiveType -> "jlong"
        type == String::class.java -> "jstring"
        type == IntArray::class.java -> "jintArray"
        type == FloatArray::class.java -> "jfloatArray"
        type == ByteArray::class.java -> "jbyteArray"
        type.isArray -> "jobjectArray"
        else -> "jobject"
    }

    private fun fieldType(kind: String): Class<*> = when (kind) {
        "I" -> Int::class.javaPrimitiveType!!
        "J" -> Long::class.javaPrimitiveType!!
        "F" -> Float::class.javaPrimitiveType!!
        "D" -> Double::class.javaPrimitiveType!!
        "S" -> String::class.java
        "A" -> IntArray::class.java
        else -> error("unknown binding kind $kind")
    }

    /** Member names of `struct [name] { ... };` in the types header (plain declarations only). */
    private fun headerFields(name: String): List<String> {
        val body = Regex("struct $name\\s*\\{(.*?)\\n\\};", RegexOption.DOT_MATCHES_ALL).find(stripComments(typesHeader))!!.groupValues[1]
        return body.lines().mapNotNull { line ->
            Regex("^\\s*[\\w:<>]+\\s+(\\w+)(?:\\[\\w+])?\\s*(?:=[^;]*)?;").find(line)?.groupValues?.get(1)
        }
    }
}
