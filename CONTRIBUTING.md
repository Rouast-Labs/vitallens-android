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

The SDK also sends its version to the API as part of the origin (`vitallens-android_<version>`), taken from the same version in the root `build.gradle.kts` (via `BuildConfig`), so there is nothing else to keep in sync.

To cut a new release:

1. **Get your changes into `main`:** work on `dev` and merge it into `main` with a PR (CI runs on PRs to `main`).
2. **Create the version commit and tag on `main`:**
    ```bash
    git switch main
    git pull origin main
    ./scripts/release.sh 0.2.1
    ```
    This sets `allprojects { version = "..." }` in the root `build.gradle.kts` and the version in the README install snippet, commits them as `0.2.1` and creates the tag `v0.2.1`. Nothing is pushed yet. It refuses to run unless you are on an up-to-date `main` with a clean working tree.
3. **Push the commit and the tag:**
    ```bash
    git push origin main --follow-tags
    ```
4. **Sync `dev`:**
    ```bash
    git switch dev
    git merge main
    git push origin dev
    ```

The release workflow requires manual approval (via the `maven-central` GitHub Environment)
before publishing, and needs `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` plus a GPG signing key
(`GPG_PRIVATE_KEY`/`GPG_PASSPHRASE`/`GPG_KEY_ID`) configured as environment secrets.


## User-facing wording

The on-screen feedback messages (Start screen guide, status and issue messages in the Scan and Monitor views) are kept mostly consistent across the JS, iOS and Android SDKs, and match the guidance on the [Accuracy & Guidelines](https://docs.rouast.com/guides/accuracy/) page. When you change any of them, update the other SDKs too (and their tests), or at least keep the wording close.
