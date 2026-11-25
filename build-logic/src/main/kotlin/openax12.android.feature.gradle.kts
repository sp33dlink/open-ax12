// Feature module: a screen (or set of tabs). Features never depend on each other.
plugins {
    id("openax12.android.ui")
}

dependencies {
    "implementation"(project(":core:runtime"))
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:widgets"))
    "implementation"(project(":core:navigation"))

    "implementation"(catalog.lib("androidx-lifecycle-runtime-compose"))
    "implementation"(catalog.lib("androidx-lifecycle-viewmodel-compose"))
    "implementation"(catalog.lib("androidx-activity-compose"))
    "implementation"(catalog.lib("androidx-core-ktx"))
}
