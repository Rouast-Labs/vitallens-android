plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// Single source of truth for the SDK's own release version (distinct from vitallens-core's
// independently-versioned Maven coordinate) — bump this and tag `vX.Y.Z` to cut a release. Every
// publishable module (vitallens-inference, vitallens, vitallens-ui) reads it via this project's
// own `version`, matching how vitallens-ios/-python/-core each version their own package
// independently rather than syncing to one another's number.
allprojects {
    version = "0.1.0"
}
