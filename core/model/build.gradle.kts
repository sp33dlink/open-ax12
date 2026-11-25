plugins {
    id("openax12.jvm.library")
    id("openax12.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
}
