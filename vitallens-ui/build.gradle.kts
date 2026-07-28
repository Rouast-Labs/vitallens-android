import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.rouast.vitallens.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
