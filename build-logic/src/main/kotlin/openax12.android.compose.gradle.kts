// Android library that needs the Compose compiler and runtime but draws no UI
// (CompositionLocals, navigation keys). UI modules use openax12.android.ui instead.
import com.android.build.api.variant.HostTestBuilder

plugins {
    id("openax12.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
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

dependencies {
    "implementation"(platform(catalog.lib("androidx-compose-bom")))
    "implementation"(catalog.lib("androidx-compose-runtime"))
}
