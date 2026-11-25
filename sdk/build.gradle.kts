plugins {
    id("openax12.android.library")
}

android {
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        ndk {
            abiFilters += Ax12Build.ABI
        }
    }

    // ANDROID_STL=c++_shared is set inside CMakeLists.txt (see the comment there).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    packaging {
        jniLibs {
            // The prebuilt SDK/Qt libraries must reach the APK byte-identical.
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    api(project(":core:radio"))
}

// The RadioMaster SDK (binaries, headers, licence texts) is not committed: scripts/fetch-sdk.sh
// downloads the pinned upstream commit and verifies it against sdk/SDK_FETCH.sha256. It runs
// automatically before anything in this module is built when one of those files is missing.
val sdkFetchScript: File = rootProject.file("scripts/fetch-sdk.sh")
val requiredSdkFiles: List<File> = rootProject.file("sdk/SDK_FETCH.sha256").readLines()
    .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }
    .filter { it.startsWith("sdk/") }
    .map { rootProject.file(it) }

val fetchSdk = tasks.register<Exec>("fetchSdk") {
    group = "build setup"
    description = "Downloads and verifies the RadioMaster SDK files if any is missing (scripts/fetch-sdk.sh)."
    val files = requiredSdkFiles
    onlyIf("an SDK file is missing") { files.any { !it.isFile } }
    workingDir = rootProject.projectDir
    commandLine("bash", sdkFetchScript.absolutePath)
}

tasks.named("preBuild") { dependsOn(fetchSdk) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchSdk) }
