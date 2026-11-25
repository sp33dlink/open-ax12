plugins {
    id("openax12.android.library")
    id("openax12.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:radio"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
