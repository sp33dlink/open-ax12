// Android library with Compose UI, Material 3 and preview screenshot rendering
// (see CLAUDE.md > Visual checks).
plugins {
    id("openax12.android.compose")
}

// Applied imperatively: the plugin refuses to load unless
// android.experimental.enableScreenshotTest=true is set in gradle.properties, which is not the
// case while Gradle generates accessors for this precompiled script.
apply(plugin = "com.android.compose.screenshot")

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    "implementation"(catalog.lib("androidx-compose-foundation"))
    "implementation"(catalog.lib("androidx-compose-animation"))
    "implementation"(catalog.lib("androidx-compose-ui"))
    "implementation"(catalog.lib("androidx-compose-ui-graphics"))
    "implementation"(catalog.lib("androidx-compose-material3"))
    "implementation"(catalog.lib("androidx-compose-ui-tooling-preview"))
    "debugImplementation"(catalog.lib("androidx-compose-ui-tooling"))

    "screenshotTestImplementation"(platform(catalog.lib("androidx-compose-bom")))
    "screenshotTestImplementation"(catalog.lib("screenshot-validation-api"))
    "screenshotTestImplementation"(catalog.lib("androidx-compose-ui-tooling"))
}
