import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rouast.vitallens"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // api, not implementation: Protocols.kt (FaceDetecting, CameraStreaming, InputFrame)
    // exposes Rect/ImageOrientation from vitallens-inference in this module's own public API,
    // so downstream consumers (vitallens-ui, the demo app) need it on their compile classpath.
    api(projects.vitallensInference)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.face.detection)

    // Generated UniFFI Kotlin bindings + native libs for the Rust core.
    implementation(libs.vitallens.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Lets tests construct real Rust-backed UniFFI objects (e.g. via ROICalculator)
    // instead of throwing UnsatisfiedLinkError under the plain Android AAR above.
    testImplementation(libs.vitallens.core.jvm)
    // android.graphics.Bitmap / androidx.camera.view.PreviewView are "Stub!"-throwing
    // placeholders under a plain JVM unit test (no Robolectric here) — needed to fake
    // references for tests that don't care about real pixel/view behavior.
    testImplementation(libs.mockito.kotlin)

    // Real-device/emulator instrumented tests (androidTest, not test) for CameraSource/
    // FaceDetector/FileSource — the parts that need a real Android runtime, ML Kit, and a
    // real camera to verify meaningfully rather than just compile.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // android.graphics.Bitmap needs a real reference for tests that don't care about actual
    // pixel content — a real android.graphics.Bitmap is otherwise expensive/awkward to construct
    // just to satisfy a type, even under a real instrumented-test Android runtime.
    androidTestImplementation(libs.mockito.kotlin)
}
