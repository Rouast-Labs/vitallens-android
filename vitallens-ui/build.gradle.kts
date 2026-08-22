import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.rouast.vitallens.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
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
    // api, not implementation: composables here expose types from both modules in their own
    // public signatures (e.g. ScanScreen(onComplete: (VitalLensResult) -> Unit)), so downstream
    // consumers (the demo app) need them on their compile classpath too — implementation()
    // doesn't propagate. Same category of issue already hit for Rect/Protocols.kt and
    // okhttp3.HttpUrl/ApiInference.
    api(projects.vitallens)
    api(projects.vitallensInference)

    // Neither vitallens nor vitallens-inference expose com.rouast.vitallens.core (the generated
    // bindings package) via api(), so it isn't visible transitively here — VitalInfoCache calls
    // getVitalInfo directly. api(), not implementation(): VitalInfoCache.getInfo() returns
    // VitalInfo in this module's own public API, so consumers need it on their classpath too.
    api(libs.vitallens.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Lets tests call real Rust-backed functions (e.g. getVitalInfo) instead of throwing
    // UnsatisfiedLinkError under the plain Android AAR pulled in via api(projects.vitallens).
    testImplementation(libs.vitallens.core.jvm)

    // Compose UI needs a real composition context (AndroidView, layout, etc.) that plain JVM
    // tests can't provide — instrumented tests (androidTest, not test) for this module's
    // composables, matching the pattern already established in vitallens for CameraX/ML
    // Kit/native-decode-dependent code.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ==========================================
// Publishing (Maven Central via the Central Portal)
// ==========================================

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            JavadocJar.Empty(),
            SourcesJar.Sources(),
            "release",
        )
    )
    publishToMavenCentral()

    // Only sign when credentials are actually present — see vitallens-inference's identical
    // block for why this is conditional.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    coordinates("com.rouast", "vitallens-ui", version.toString())

    pom {
        name.set("VitalLens UI")
        description.set("Pre-built Jetpack Compose screens (Scan, Monitor) for the VitalLens Android SDK.")
        url.set("https://github.com/Rouast-Labs/vitallens-android")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("rouast-labs")
                name.set("Rouast Labs")
                url.set("https://github.com/Rouast-Labs")
            }
        }

        scm {
            url.set("https://github.com/Rouast-Labs/vitallens-android")
            connection.set("scm:git:git://github.com/Rouast-Labs/vitallens-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rouast-Labs/vitallens-android.git")
        }
    }
}
