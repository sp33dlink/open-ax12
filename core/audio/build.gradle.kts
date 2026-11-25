plugins {
    id("openax12.android.library")
}

android {
    // The on-device smoke check is its own instrumentation (no test libraries needed), see
    // src/androidTest/.../SmokeInstrumentation.kt.
    defaultConfig.testInstrumentationRunner = "org.openax12.core.audio.SmokeInstrumentation"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}
