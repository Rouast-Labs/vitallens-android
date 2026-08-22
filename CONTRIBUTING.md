# Contributing & Development Guide

This guide covers how to set up, test, and build the `vitallens-android` SDK.

## Prerequisites

- **Android Studio** (latest stable) or a standalone Android SDK.
- **JDK 17** — Android Studio's bundled JBR works and needs no separate install
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home` on macOS); there's no
  requirement to have a system JDK on `PATH`.
- **compileSdk 36** (installed automatically by Android Studio/Gradle on first sync).
- A physical device or emulator for instrumented tests (see below) — some of this SDK's
  behavior (CameraX, ML Kit face detection, `MediaCodec` video decode) can only be verified on
  a real Android runtime, not a plain JVM.

## Development Setup

```bash
# Clone the repo
git clone https://github.com/Rouast-Labs/vitallens-android.git
cd vitallens-android

# Open in Android Studio, or run Gradle directly from the command line:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # macOS, if no system JDK
./gradlew build
```

## Building

```bash
./gradlew build
```

This compiles, lints, and unit-tests all four modules: `vitallens-inference`, `vitallens`,
`vitallens-ui`, and the demo app (`app`).

## Testing

The test suite is split the same way `vitallens-ios`'s is: fast logic tests that run on a plain
JVM, and instrumented tests that need a real Android runtime.

### Unit Tests (Command Line)

Pure logic — parsing, buffering, ROI math, network models — runs on a plain JVM with no
device/emulator required:

```bash
./gradlew test
```

### Instrumented Tests (Device/Emulator Required)

Anything touching CameraX, ML Kit, `MediaCodec` decode, or Compose UI composition must run on a
real device or emulator:

```bash
./gradlew connectedAndroidTest
```

Some of these are real end-to-end integration tests that call the live VitalLens API. Provide
credentials via environment variables (matching how `vitallens-ios`'s `IntegrationTests.swift`
reads `VITALLENS_API_KEY`/`VITALLENS_BASE_URL`) or in `local.properties` for local development:

```properties
# local.properties (gitignored)
VITALLENS_API_KEY=your_actual_api_key
VITALLENS_BASE_URL=https://api-dev.rouast.com/vitallens-dev
```

## Release Process

Each module (`vitallens-inference`, `vitallens`, `vitallens-ui`) publishes its own coordinate
under `com.rouast` to Maven Central via the [Central Portal](https://central.sonatype.com/),
using the same `com.vanniktech.maven.publish` setup as `vitallens-core`'s Kotlin/Android
artifact. A GitHub Action publishes all three and creates a GitHub Release whenever a semantic
version tag is pushed.

To cut a new release:

1. **Bump the version** in the root `build.gradle.kts` (`allprojects { version = "..." }`).
2. **Tag the release:**
    ```bash
    git tag v0.1.0
    ```
3. **Push the tag:**
    ```bash
    git push origin v0.1.0
    ```

The release workflow requires manual approval (via the `maven-central` GitHub Environment)
before publishing, and needs `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` plus a GPG signing key
(`GPG_PRIVATE_KEY`/`GPG_PASSPHRASE`/`GPG_KEY_ID`) configured as environment secrets.
