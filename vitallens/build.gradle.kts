import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Credentials for IntegrationTest's real-API tests (androidTest, not test — see that file).
 *
 * Mirrors vitallens-ios's IntegrationTests.swift, which reads VITALLENS_API_KEY/
 * VITALLENS_BASE_URL via ProcessInfo.processInfo.environment: since Swift's whole test process
 * shares the real OS environment, an env var set once (in the Xcode scheme locally, or a CI
 * secret) is transparently visible everywhere, including inside APIInference's own lookup.
 *
 * Android has no such shortcut: an instrumented test runs in a separate process on a
 * device/emulator that does not inherit the host shell's environment. Gradle (running on the
 * host) is what actually sees `System.getenv()`/local.properties, so it must thread the value
 * through explicitly via testInstrumentationRunnerArguments, which the test then reads via
 * InstrumentationRegistry.getArguments() — not System.getenv().
 *
 * local.properties (already gitignored, already holds sdk.dir) is the local-dev-convenience
 * option; a real shell-exported env var (matching CI) always takes precedence, so CI needs no
 * separate local.properties handling.
 */
fun integrationTestCredential(key: String): String {
    System.getenv(key)?.let { return it }
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    return localProperties.getProperty(key) ?: ""
}

android {
    namespace = "com.rouast.vitallens"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["VITALLENS_API_KEY"] = integrationTestCredential("VITALLENS_API_KEY")
        testInstrumentationRunnerArguments["VITALLENS_BASE_URL"] = integrationTestCredential("VITALLENS_BASE_URL")
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
}
