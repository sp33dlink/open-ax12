// The single application module. Compose + built-in Kotlin, arm64 only.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// See openax12.android.ui for why this plugin is applied imperatively.
apply(plugin = "com.android.compose.screenshot")

android {
    configureAndroidCommon(this)
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        targetSdk = Ax12Build.TARGET_SDK
        ndk {
            abiFilters += Ax12Build.ABI
        }
    }

    buildFeatures {
        compose = true
    }
}

// AGP 9.5 registers a generate<Variant>ComposePreviewRunfiles task for every variant of a Compose
// module and fails to create it for variants without unit tests (release, by default), which
// breaks `tasks --all`. Keeping unit tests enabled for all variants avoids that.
androidComponents {
    beforeVariants { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// assemble<Variant> also proves that the prebuilt SDK libraries are in the APK unmodified.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNativeLibsTask>("verify${variantName}NativeLibs") {
            group = "verification"
            description = "Checks the prebuilt SDK libraries inside the ${variant.name} APK against sdk/SDK_CHECKSUMS.sha256."
            apkDirectory = variant.artifacts.get(SingleArtifact.APK)
            builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
            checksums = rootProject.layout.projectDirectory.file("sdk/SDK_CHECKSUMS.sha256")
            report = layout.buildDirectory.file("reports/nativeLibs/${variant.name}.txt")
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = Ax12Build.JVM_TARGET
    }
}

dependencies {
    "implementation"(platform(catalog.lib("androidx-compose-bom")))
    "implementation"(catalog.lib("androidx-compose-runtime"))
    "implementation"(catalog.lib("androidx-compose-foundation"))
    "implementation"(catalog.lib("androidx-compose-animation"))
    "implementation"(catalog.lib("androidx-compose-ui"))
    "implementation"(catalog.lib("androidx-compose-ui-graphics"))
    "implementation"(catalog.lib("androidx-compose-material3"))
    "implementation"(catalog.lib("androidx-compose-ui-tooling-preview"))
    "debugImplementation"(catalog.lib("androidx-compose-ui-tooling"))
    "implementation"(catalog.lib("kotlinx-coroutines-android"))

    "screenshotTestImplementation"(platform(catalog.lib("androidx-compose-bom")))
    "screenshotTestImplementation"(catalog.lib("screenshot-validation-api"))
    "screenshotTestImplementation"(catalog.lib("androidx-compose-ui-tooling"))

    "testImplementation"(catalog.lib("junit"))
    "testImplementation"(catalog.lib("kotlinx-coroutines-test"))
    "testImplementation"(catalog.lib("turbine"))
}
