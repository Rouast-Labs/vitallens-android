import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * Reads VITALLENS_API_KEY/VITALLENS_PROXY_URL for BuildConfig, same convention as the
 * `integrationTestCredential` helper in vitallens/build.gradle.kts: a real shell-exported env
 * var takes precedence, local.properties (gitignored) is the local-dev-convenience fallback.
 */
fun demoCredential(key: String): String {
    System.getenv(key)?.let { return it }
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    return localProperties.getProperty(key) ?: ""
}

android {
    namespace = "com.rouast.vitallens.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rouast.vitallens.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "VITALLENS_API_KEY", "\"${demoCredential("VITALLENS_API_KEY")}\"")
        buildConfigField("String", "VITALLENS_PROXY_URL", "\"${demoCredential("VITALLENS_PROXY_URL")}\"")
        // Only needed for a non-production API key (e.g. a dev-environment-scoped key) —
        // ScanScreen/MonitorScreen/FileScreen's baseUrl parameter, threaded through
        // VitalLensClientFactory.kt, is what actually makes this override effective.
        buildConfigField("String", "VITALLENS_BASE_URL", "\"${demoCredential("VITALLENS_BASE_URL")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.vitallensUi)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)
}
