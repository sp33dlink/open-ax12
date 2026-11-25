plugins {
    id("openax12.android.compose")
    id("openax12.serialization")
}

dependencies {
    api(libs.androidx.navigation3.runtime)
    api(libs.kotlinx.serialization.json)
}
