# vitallens-android

Kotlin/Android port of `vitallens-ios`. Same public API surface, same module
separation, idiomatic Kotlin/Coroutines instead of Swift/async-await, JNI via
UniFFI-generated Kotlin bindings instead of Swift FFI.

Reference repos (read-only, added via `/add-dir`, never modify):
- `../vitallens-ios` — source of truth for public API shape and behavior.
- `../vitallens-core` — Rust core. Android build tooling for this is **out of
  scope** here; treat its generated Kotlin bindings (`VitalLensCore.kt`) and
  compiled `.so`/`.aar` as a given dependency, not something to build. Now
  published to Maven Central as `com.rouast:vitallens-core` (real artifact,
  packaging `aar`) — see the Ground rules bullet below for coordinates.

## Ground rules

- Match the Swift SDK's public API surface and calling patterns as closely as
  Kotlin idiom allows. When in doubt, check the equivalent Swift file in
  `../vitallens-ios` before inventing a new shape.
- Don't reach for the Rust core's Android build — `VitalLensCore.kt` and its
  native libs are consumed as a published dependency,
  `com.rouast:vitallens-core` (Maven Central, packaging `aar`, current
  version tracked as `vitallensCore` in `libs.versions.toml`). Its AAR
  metadata pins `minCompileSdk=36`/`minAGP=1.0.0`, consistent with our own
  floor. It brings `net.java.dev.jna:jna` (aar) and `kotlin-stdlib` as
  transitive dependencies and ships `jniLibs` for all four standard ABIs;
  generated classes live under `com.rouast.vitallens.core`.
- Prefer small, reviewable PRs/commits over one big dump. Build one file or
  one small group of related files at a time, run `./gradlew build`, then move on.
- No native code (no libyuv/JNI for image processing) in the first pass —
  pure Kotlin/JVM `Bitmap`/`YuvImage`/`Matrix` for cropping, scaling, rotation,
  YUV→RGB conversion. This will be slower than the Swift vImage path; that's
  accepted for now and revisited only after profiling.
- minSdk 26, targetSdk latest stable, Kotlin coroutines + Flow throughout,
  Jetpack Compose for UI, OkHttp for networking, kotlinx.serialization for
  JSON.
- Build tooling: AGP 9.1.1 + Kotlin 2.4.0, using **AGP's built-in Kotlin
  support**. Do not apply `org.jetbrains.kotlin.android` or
  `org.jetbrains.kotlin.jvm` to any module — as of AGP 9.0 this is a hard
  build error, not a deprecation warning (there is no supported opt-out back
  to the classic plugin). Kotlin *feature* plugins that aren't built in still
  need explicit application: `org.jetbrains.kotlin.plugin.serialization`,
  `org.jetbrains.kotlin.plugin.compose`. See
  https://kotl.in/gradle/agp-built-in-kotlin.
- Never fetch binary Gradle artifacts (`gradle-wrapper.jar`, distribution
  zips) through a text-oriented fetch tool — it silently corrupts them
  ("no main manifest attribute" when run). Regenerate `gradle/wrapper/` via a
  real `gradle wrapper --gradle-version <x> --distribution-type bin` run
  instead — a distribution cached under `~/.gradle/wrapper/dists/` (e.g.
  from a prior Android Studio sync) or a `brew install gradle` both work.
  This machine has no system JDK; Android Studio's bundled JBR
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) is a
  usable `JAVA_HOME` for running Gradle from the command line.
- Dependency versions are capped, not "latest", specifically to keep the
  published AAR's `minCompileSdk`/`minAndroidGradlePluginVersion` metadata
  low (currently 36 / 8.9.1 — verified via each candidate's
  `META-INF/com/android/build/gradle/aar-metadata.properties`). Every AAR
  dependency stamps a floor that *propagates to every consumer of this SDK*
  — e.g. `androidx.core:core-ktx:1.19.0` and
  `androidx.lifecycle:lifecycle-runtime-compose:2.11.0` force
  `minCompileSdk=37`/`minAGP=9.1.0` on downstream apps, which is far ahead
  of what most Android apps are on. When bumping any `androidx.*` version in
  `libs.versions.toml`, check its AAR metadata (same properties file) before
  assuming "newer is fine" — a routine dependency bump can silently lock out
  most integrators. Our own build tooling version (AGP 9.1.1) does *not*
  similarly propagate — `minAndroidGradlePluginVersion` in our own published
  AAR was verified to stay at the default `1.0.0` regardless.

## Development workflow: TDD

Every file with real logic (parsing, computation, conditionals, encode/decode)
follows red-green-refactor, in this order, as separate steps — not combined
into one prompt turn:

1. RED — Port or write the test file first, based on the corresponding Swift
   test in ../vitallens-ios (VitalLensInferenceTests / VitalLensTests /
   VitalLensUITests) if one exists for that source file. If no Swift test
   exists for it, write Kotlin tests from the doc comments and behavior in
   the Swift source itself. Do not write the Kotlin implementation yet.
2. Run the tests and confirm they fail (compile failure because the
   production class doesn't exist yet counts as red).
3. Commit the failing test file alone as a checkpoint.
4. GREEN — Port the implementation to make the tests pass. Do not modify the
   tests to make them pass — if a test seems wrong, stop and flag it rather
   than loosening it.
5. REFACTOR — clean up once green, tests must stay green.

Exception: pure data-shape files with no real logic (simple data classes,
enums with no computed behavior) don't need the full ceremony — write a thin
verification test immediately alongside the port rather than a separate
red/green round trip, since there's no meaningful "red" state for a file
that's just declaring shape.

## Module structure

```
vitallens-android/
├── vitallens-inference/     # pure Kotlin/JVM (no Android framework deps where avoidable)
│   ├── network/              # APIInference, NetworkModels
│   ├── buffer/                # BufferManager, FrameBuffer
│   ├── model/                  # VitalLensResult, Vital, Waveform, FaceData, StateData
│   ├── ErrorTypes.kt
│   ├── InferenceStrategy.kt
│   ├── LocalInferenceBase.kt
│   ├── Rect.kt                   # framework-agnostic normalized ROI type
│   ├── ROICalculator.kt
│   └── SessionAdapter.kt        # bridges to VitalLensCore.kt (generated)
├── vitallens/  # Android library: camera, face detection, image processing
│   ├── camera/                # CameraSource (CameraX), PassiveSource, FileSource
│   ├── vision/                  # FaceDetector (ML Kit)
│   ├── image/                    # ImageProcessor (pure Kotlin)
│   ├── roi/                        # ROIStrategy, FaceROIStrategy
│   ├── FileProcessor.kt
│   ├── StreamProcessor.kt
│   └── VitalLens.kt                  # main client class
└── vitallens-ui/             # Jetpack Compose
    ├── CameraPreview.kt
    ├── ScanScreen.kt
    ├── MonitorScreen.kt
    ├── FileScreen.kt
    ├── ResultScreen.kt
    ├── StartScreen.kt
    └── WaveformChart.kt
```

Publishing split mirrors SPM: `vitallens` + `vitallens-ui`
together form the "full SDK" product; `vitallens-inference` is usable
standalone (headless / bring-your-own-frames via CoreML-analog custom
strategies).

## Concurrency mapping (Swift → Kotlin)

| Swift | Kotlin |
|---|---|
| `actor` | plain class, internal state guarded by `kotlinx.coroutines.sync.Mutex`, all mutating methods are `suspend fun` |
| `AsyncStream<T>` (single input pipeline, e.g. camera frames) | `Channel<T>` wrapped as `Flow<T>` (`receiveAsFlow()`), or `callbackFlow { }` when bridging a callback-based Android API (CameraX `ImageAnalysis.Analyzer`, ML Kit listeners) |
| `AsyncStream<VitalLensResult>` (fan-out to UI) | `SharedFlow<VitalLensResult>` |
| `async throws -> T` | `suspend fun ... : T` (throws normal Kotlin exceptions) |
| `Task { ... }` | `scope.launch { ... }` — every class that owns background work owns a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (or `.IO` for network/file work), cancelled explicitly in its `stop()`/`close()` |
| `@Sendable` closure | plain lambda — no annotation needed, just don't capture mutable state without a Mutex |
| `NSLock` / `withLock` | `Mutex().withLock { }` |
| `CVPixelBuffer` | `android.media.Image` (from CameraX `ImageAnalysis`) or `Bitmap` once converted |
| `CGRect` (normalized ROI) | small `data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)` — do not use `android.graphics.RectF` directly as the public-facing ROI type, keep it framework-agnostic like the Swift `CGRect` extension does |

Every class that starts background coroutines must have an explicit
`stop()`/`close()` that cancels its scope — mirrors Swift's deterministic
`stopStream()`/`stop()` cleanup. Don't rely on `Dispatchers.Default`'s global
scope for anything that needs cancellation.

## File-by-file port map

### vitallens-inference (from Sources/VitalLensInference/)

| Swift source | Kotlin target | Notes |
|---|---|---|
| `Errors.swift` | `ErrorTypes.kt` | sealed class `VitalLensException` (or exception hierarchy) instead of `enum: Error` |
| `NetworkModels.swift` | `network/NetworkModels.kt` | `data class` + `@Serializable`, snake_case via `@SerialName` |
| *(no direct source — `CGRect` usage extracted from `FrameBuffer.swift`/`BufferManager.swift`/`ROICalculator.swift`/`SessionAdapter.swift`/`VitalLensResult.swift`)* | `Rect.kt` | `data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)` with `maxX`/`maxY` computed properties, mirroring `CGRect`'s role as a universal Foundation type both Swift targets get for free. Lives here (not `vitallens`) because it's consumed pervasively inside `VitalLensInference` itself; `vitallens`'s later `Protocols.kt` port reuses this type instead of redefining it — see "Open decisions" note on the `api()` dependency implication. |
| `APIInference.swift` | `network/ApiInference.kt` | OkHttp `Call` wrapped with `suspendCancellableCoroutine`, or OkHttp's Kotlin coroutine extensions; gzip via `GZIPOutputStream` |
| `BufferManager.swift` | `buffer/BufferManager.kt` | actor → Mutex-guarded class |
| `FrameBuffer.swift` | `buffer/FrameBuffer.kt` | direct port, no concurrency primitives needed (owned exclusively by BufferManager) |
| `InferenceStrategy.swift` | `InferenceStrategy.kt` | interface with suspend fun `infer(...)` |
| `LocalInferenceBase.swift` | `LocalInferenceBase.kt` | abstract class, `open suspend fun predict(...)` |
| `ROICalculator.swift` | `ROICalculator.kt` | calls into generated `VitalLensCore.kt` |
| `SessionAdapter.swift` | `SessionAdapter.kt` | extension-function equivalents as top-level Kotlin extension functions |
| `VitalLensResult.swift` | `model/VitalLensResult.kt` + `model/Vital.kt` + `model/Waveform.kt` + `model/FaceData.kt` + `model/StateData.kt` | split into separate files per Kotlin convention; custom `@Serializable` decode logic for the dynamic-key JSON parsing (waveforms/vitals dictionaries) |

### vitallens (from Sources/VitalLens/)

| Swift source | Kotlin target | Notes |
|---|---|---|
| `Protocols.swift` | `InputFrame.kt`, `CameraStreaming.kt`, `FaceDetecting.kt` | interfaces + data classes, split per Kotlin convention. `Rect` itself is *not* redefined here — reuse `com.rouast.vitallens.inference.Rect` from `vitallens-inference` (ported in Phase 1; see that module's table). |
| `CameraSource.swift` | `camera/CameraSource.kt` | CameraX `ImageAnalysis` use case; emits `Flow<InputFrame>` via `callbackFlow` |
| `PassiveSource.swift` | `camera/PassiveSource.kt` | `Channel<InputFrame>`-backed, `inject()` method |
| `FileSource.swift` | `camera/FileSource.kt` | `MediaExtractor`/`MediaCodec` (or `MediaMetadataRetriever` for a simpler first pass — flag this as a decision point, see Phase 2 notes) |
| `FaceDetector.swift` | `vision/FaceDetector.kt` | ML Kit `FaceDetector`, wrapped with `suspendCancellableCoroutine`; replicate the Vision-bottom-left → top-left coordinate conversion logic exactly |
| `ImageProcessor.swift` | `image/ImageProcessor.kt` | pure Kotlin: `Bitmap`/`Matrix`/`YuvImage.compressToJpeg`-free manual YUV→RGB loop, crop, scale, rotate/reflect. This is the highest-effort port — budget real time here. |
| `ROIStrategies.swift` | `roi/ROIStrategy.kt`, `roi/FaceROIStrategy.kt` | direct port |
| `StreamProcessor.swift` | `StreamProcessor.kt` | actor → Mutex-guarded class + owned CoroutineScope |
| `FileProcessor.swift` | `FileProcessor.kt` | direct port |
| `VitalLens.swift` | `VitalLens.kt` | main client; lifecycle observers use `ProcessLifecycleOwner` instead of `NotificationCenter` background/foreground notifications |

### vitallens-ui (from Sources/VitalLensUI/)

| Swift source | Kotlin target | Notes |
|---|---|---|
| `CameraPreview.swift` | `CameraPreview.kt` | `AndroidView` wrapping CameraX `PreviewView` |
| `VitalLensStartView.swift` | `StartScreen.kt` | direct Compose port |
| `VitalLensScanView.swift` | `ScanScreen.kt` | direct Compose port; state machine (`ScanState`) ports 1:1 as a Kotlin sealed class/enum |
| `VitalLensMonitorView.swift` | `MonitorScreen.kt` | direct Compose port |
| `VitalLensFileView.swift` | `FileScreen.kt` | file picker via `ActivityResultContracts.GetContent()` instead of `PhotosPicker`/`fileImporter` |
| `VitalLensResultView.swift` | `ResultScreen.kt` | direct Compose port |
| `WaveformView.swift` | `WaveformChart.kt` | no Swift Charts equivalent — hand-roll with Compose `Canvas`/`Path`, same visual contract (line, no axes) |

## Phased implementation order

Work in this order — each phase should build and (where applicable) pass
tests before starting the next.

**Phase 0 — Scaffolding.** Gradle multi-module skeleton, version catalog,
empty module stubs, CI-less local build green.

**Phase 1 — `vitallens-inference`.** No Android framework dependency needed
except where noted. This is the most mechanical, lowest-risk phase — good
place to build trust in the Claude Code workflow before tackling camera/UI.
Includes unit tests ported from `VitalLensInferenceTests` where behavior is
testable without hardware.

**Phase 2 — `vitallens` minus UI.** Camera, face detection,
image processing, stream/file processing, the `VitalLens` client class.
Decision point to flag explicitly when you reach `FileSource`: start with
`MediaMetadataRetriever.getFrameAtTime` (simple, adequate for the file-import
use case) rather than `MediaExtractor`/`MediaCodec` (more code, needed only
if frame-accurate/streaming decode becomes a requirement).

**Phase 3 — `vitallens-ui`.** Compose screens, wired to a small demo app
module (`:app`) mirroring `Demo/VitalLensDemo`.

**Phase 4 — Demo app + docs.** Equivalent of `Demo/VitalLensDemo`, plus
`docs/` ported (`ref.md`, `views.md`, `advanced.md`, `proxies.md`).

## Open decisions to revisit later (not blocking initial port)

- `ImageProcessor` performance: pure Kotlin now; libyuv-via-JNI or pushing
  crop/scale/color-convert into the Rust core are the two later options if
  profiling shows it's a bottleneck.
- Networking: OkHttp chosen over Ktor for now (no KMP ambitions currently).
- `FileSource` decode strategy: see Phase 2 note above.
- `vitallens`'s `build.gradle.kts` currently declares
  `implementation(projects.vitallensInference)`. If Phase 2's `Protocols.kt`
  (`FaceDetecting.kt` etc.) exposes `Rect` (from `vitallens-inference`) in
  its own public API surface, that needs to become `api(projects.vitallensInference)`
  instead — `implementation` doesn't propagate the type to `vitallens-ui`/the
  demo app's compile classpath. Revisit when `Protocols.kt` is actually
  ported.
