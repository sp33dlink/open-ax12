plugins {
    `kotlin-dsl`
}

dependencies {
    // `implementation` (not compileOnly): the precompiled script plugins below apply these
    // plugins by id, so they must be on the runtime classpath of every build script.
    implementation(libs.gradleplugin.android)
    implementation(libs.gradleplugin.kotlin)
    implementation(libs.gradleplugin.kotlin.serialization)
    implementation(libs.gradleplugin.compose.compiler)
    implementation(libs.gradleplugin.compose.screenshot)
}
