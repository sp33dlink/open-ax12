plugins {
    id("openax12.android.ui")
}

dependencies {
    implementation(project(":core:model"))
    api(libs.androidx.compose.material.icons.extended)
    api(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    // BackHandler for the in-tree dialogs (AxOverlayHost).
    implementation(libs.androidx.activity.compose)
}
