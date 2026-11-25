// Pure Kotlin/JVM library (no Android): :core:model, :core:engine.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = Ax12Build.JAVA
    targetCompatibility = Ax12Build.JAVA
}

kotlin {
    compilerOptions {
        jvmTarget = Ax12Build.JVM_TARGET
    }
}

dependencies {
    "testImplementation"(catalog.lib("junit"))
    "testImplementation"(catalog.lib("kotlinx-coroutines-test"))
    "testImplementation"(catalog.lib("turbine"))
}
