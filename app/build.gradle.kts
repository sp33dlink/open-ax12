plugins {
    id("openax12.android.application")
}

// Versioning: versionCode = number of commits on HEAD (strictly increasing on master, so every
// dev build installs over the previous one). versionName = the base version below; builds that
// are not release builds (-Popenax12.release=true, used by the tag pipeline) get
// "-dev.<commits>+<short sha>" appended.
val baseVersionName = "0.2"

fun gitOutput(vararg args: String): Provider<String> =
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }

val commitCount: Int = gitOutput("rev-list", "--count", "HEAD").orNull?.toIntOrNull() ?: 1
val shortSha: String = gitOutput("rev-parse", "--short=7", "HEAD").orNull?.takeIf { it.matches(Regex("[0-9a-f]{7}")) } ?: "local"
val isRelease: Boolean = providers.gradleProperty("openax12.release").orNull == "true"

// Signing: the release job in CI provides the release key through these environment variables
// (decoded from the secrets of the GitHub environment "release", see .github/workflows/android.yml).
// Without them (local builds, other CI jobs) debug uses the debug key and release APKs stay unsigned.
val signingKeyFile: String? = providers.environmentVariable("OPENAX12_KEYSTORE_FILE").orNull
    ?.takeIf { file(it).isFile }

android {
    defaultConfig {
        applicationId = "org.openax12.app"
        versionCode = commitCount
        versionName = if (isRelease) baseVersionName else "$baseVersionName-dev.$commitCount+$shortSha"
    }

    if (signingKeyFile != null) {
        signingConfigs {
            create("openax12") {
                storeFile = file(signingKeyFile)
                storePassword = providers.environmentVariable("OPENAX12_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("OPENAX12_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("OPENAX12_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until keep rules for the JNI bridge and serializers exist.
            isMinifyEnabled = false
            if (signingKeyFile != null) signingConfig = signingConfigs.getByName("openax12")
        }
        debug {
            if (signingKeyFile != null) signingConfig = signingConfigs.getByName("openax12")
        }
    }

    packaging {
        jniLibs {
            // Qt resolves its sibling libraries from the extracted native library directory.
            useLegacyPackaging = true
            // The prebuilt SDK/Qt libraries must reach the APK byte-identical.
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(project(":sdk"))

    implementation(project(":core:model"))
    implementation(project(":core:radio"))
    implementation(project(":core:engine"))
    implementation(project(":core:audio"))
    implementation(project(":core:data"))
    implementation(project(":core:runtime"))
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:widgets"))

    implementation(project(":feature:home"))
    implementation(project(":feature:modelsetup"))
    implementation(project(":feature:models"))
    implementation(project(":feature:telemetry"))
    implementation(project(":feature:functions"))
    implementation(project(":feature:system"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)
}
