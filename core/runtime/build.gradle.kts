plugins {
    id("openax12.android.compose")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:radio"))
    api(project(":core:engine"))
    api(project(":core:audio"))
    api(project(":core:data"))
    implementation(libs.androidx.core.ktx)
}
