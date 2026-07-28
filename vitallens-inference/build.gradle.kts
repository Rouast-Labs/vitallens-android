import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rouast.vitallens.inference"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // api, not implementation: ApiInference's constructor exposes okhttp3.HttpUrl (proxyUrl)
    // as part of its own public API, and VitalLens.kt (in the vitallens module, a consumer of
    // this module) needs that same type on its compile classpath for its own proxyUrl parameter
    // — implementation() doesn't propagate transitively. Same category of issue CLAUDE.md already
    // flags for Rect/Protocols.kt.
    api(libs.okhttp)

    // Generated UniFFI Kotlin bindings + native libs for the Rust core.
    implementation(libs.vitallens.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Lets tests construct real Rust-backed UniFFI objects (BufferPlanner, Session)
    // instead of throwing UnsatisfiedLinkError under the plain Android AAR above.
    testImplementation(libs.vitallens.core.jvm)
    // android.graphics.Bitmap is a "Stub!"-throwing placeholder under a plain JVM unit
    // test (no Robolectric here) — needed to fake a Bitmap reference for tests that
    // don't care about actual pixel behavior, only that it flows through unchanged.
    testImplementation(libs.mockito.kotlin)
}
