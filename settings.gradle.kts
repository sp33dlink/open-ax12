pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "open-ax12"

include(":app")
include(":sdk")

include(":core:model")
include(":core:radio")
include(":core:engine")
include(":core:audio")
include(":core:data")
include(":core:runtime")
include(":core:navigation")
include(":core:designsystem")
include(":core:widgets")

include(":feature:home")
include(":feature:modelsetup")
include(":feature:models")
include(":feature:telemetry")
include(":feature:functions")
include(":feature:system")
