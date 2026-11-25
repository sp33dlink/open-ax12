import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Values shared by every convention plugin. Library versions live in `gradle/libs.versions.toml`. */
object Ax12Build {
    /** Platform `android-37.0`. Compose 1.12 / lifecycle 2.11 refuse to build against less than 37. */
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 0
    const val TARGET_SDK = 36

    /** The AX12 runs Android 9 and is never updated. */
    const val MIN_SDK = 28

    /** The SDK binaries exist for this ABI only. */
    const val ABI = "arm64-v8a"

    val JAVA: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17
}

/** The `libs` version catalog, for use inside convention plugins. */
internal val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Looks up a library alias such as `androidx-compose-ui`. */
internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in libs.versions.toml") }

/** Looks up a version alias such as `cmake`. */
internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalArgumentException("No version '$alias' in libs.versions.toml") }
        .requiredVersion

/** `:core:model` -> `org.openax12.core.model`. */
internal val Project.defaultNamespace: String
    get() = "org.openax12" + path.replace(':', '.')

/** Settings identical for the application and all Android libraries. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.apply {
        namespace = defaultNamespace
        compileSdk {
            version = release(Ax12Build.COMPILE_SDK) { minorApiLevel = Ax12Build.COMPILE_SDK_MINOR }
        }
        defaultConfig.minSdk = Ax12Build.MIN_SDK
        compileOptions.sourceCompatibility = Ax12Build.JAVA
        compileOptions.targetCompatibility = Ax12Build.JAVA
        testOptions.unitTests.isReturnDefaultValues = true
        // Required by AGP's Compose Preview tasks in every Compose module; without it merely
        // listing tasks (`tasks --all`, IDE sync) fails.
        testOptions.unitTests.isIncludeAndroidResources = true
    }
}
