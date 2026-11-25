plugins {
    id("openax12.android.library")
    // FakeRadio persists its simulated model files as JSON.
    id("openax12.serialization")
}

dependencies {
    // :core:radio must not depend on :core:engine: FakeRadio carries its own mixer / curve
    // implementation (package `fake`), kept in line with the KDoc of MixerMath / CurveMath.
    api(project(":core:model"))
}
