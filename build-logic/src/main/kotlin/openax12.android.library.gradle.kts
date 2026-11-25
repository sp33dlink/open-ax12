// Android library without Compose. Kotlin comes from AGP's built-in Kotlin support:
// do NOT apply org.jetbrains.kotlin.android.
plugins {
    id("com.android.library")
}

android {
    configureAndroidCommon(this)
}

kotlin {
    compilerOptions {
        jvmTarget = Ax12Build.JVM_TARGET
    }
}

dependencies {
    "implementation"(catalog.lib("kotlinx-coroutines-android"))

    "testImplementation"(catalog.lib("junit"))
    "testImplementation"(catalog.lib("kotlinx-coroutines-test"))
    "testImplementation"(catalog.lib("turbine"))
}
