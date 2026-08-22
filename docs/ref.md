# API Reference

The `VitalLens` class is the main entry point for the SDK. Use this class directly if you are building a custom camera experience or integrating into an existing `CameraX`/`Camera2` pipeline without using the pre-built Compose screens.

## Initialization

The `VitalLens` client constructor allows you to start with simple defaults or inject custom components for advanced integrations.

```kotlin
class VitalLens(
    context: Context,
    apiKey: String? = null,
    method: String = "vitallens",
    faceDetectionFrequency: Double = 1.0,
    globalROI: Rect? = null,
    proxyUrl: HttpUrl? = null,
    overrideFps: Double? = null,
    waveformMode: WaveformMode = WaveformMode.Incremental,
    source: CameraStreaming? = null,
    strategy: InferenceStrategy? = null,
    transformer: FrameTransformer? = null,
)
```

### Parameters

| Name | Type | Description | Default |
| --- | --- | --- | --- |
| `context` | `Context` | An Android `Context`, needed to access the camera and content resolver. | *Required* |
| `apiKey` | `String?` | Your API Key. Required if `proxyUrl` and `strategy` are not set. | `null` |
| `method` | `String` | The model version to use (e.g., `"vitallens"`, `"vitallens-2.0"`). | `"vitallens"` |
| `proxyUrl` | `HttpUrl?` | URL to a backend proxy (Recommended for production). | `null` |
| `faceDetectionFrequency` | `Double` | Frequency (Hz) to run face detection. Lower values save battery. | `1.0` |
| `globalROI` | `Rect?` | A fixed ROI (normalized `0.0`-`1.0`) to bypass face detection. | `null` |
| `overrideFps` | `Double?` | Target sampling FPS. Overrides model default if set. | `null` |
| `waveformMode` | `WaveformMode` | Waveform return mode: `Incremental` or `Global`. | `Incremental` |
| `source` | `CameraStreaming?` | A custom frame source. Defaults to standard `CameraSource`. | `null` |
| `strategy` | `InferenceStrategy?` | A custom inference backend (e.g., an on-device model). Defaults to `ApiInference`. | `null` |
| `transformer` | `FrameTransformer?` | A custom function to preprocess frames before inference. | `null` |

### Configuration Logic

The SDK configures itself based on the parameters you provide:

1. **Inference Strategy:** If you provide a `strategy`, the SDK uses it directly. If `strategy` is `null`, the SDK initializes an `ApiInference` strategy using your `apiKey` or `proxyUrl`.
2. **Camera Source:** If you provide a `source` (such as a `PassiveSource` for external camera frames), the SDK uses it. Otherwise, it initializes a standard `CameraSource` to manage the device hardware.

### Method Options

When using the default API `InferenceStrategy`, these options are available for `method`:

- `"vitallens"`: **(Recommended)** Uses the VitalLens API and automatically selects the best model available for your API key (e.g., VitalLens 2.0 with HRV support).
- `"vitallens-2.0"`: Forces the use of the VitalLens 2.0 model.
- `"vitallens-1.0"` / `"vitallens-1.1"`: Forces the use of older model versions.

### Examples

**Standard API Integration:**

```kotlin
val client = VitalLens(context = context, apiKey = "YOUR_KEY")
```

**Custom Camera with API Inference:**

```kotlin
val client = VitalLens(
    context = context,
    apiKey = "YOUR_KEY",
    source = myPassiveSource,
)
```

**Custom On-Device Inference:**

```kotlin
val client = VitalLens(
    context = context,
    strategy = MyLocalStrategy(),
)
```

See the **[Advanced](advanced.md)** documentation for details on implementing `InferenceStrategy`.

---

## Properties

### `onFaceStateChanged`

A closure triggered instantly when the face detector either finds a face or loses tracking. Useful for updating user guidance UI (e.g., "Please face the camera").

```kotlin
client.onFaceStateChanged = { isPresent ->
    if (isPresent) {
        println("Face detected!")
    } else {
        println("Face lost. Adjust position.")
    }
}
```

---

## Methods

### `startStream(preview)`

Starts the device camera, manages the processing loop, and yields results continuously via a hot Kotlin `SharedFlow`.

**Parameters:**

- `preview`: (Optional) A `PreviewView` where the live camera feed will be rendered.

**Returns:** `SharedFlow<VitalLensResult>`

```kotlin
scope.launch {
    try {
        val stream = client.startStream(preview = myPreviewView)
        stream.collect { result ->
            result.heartRate?.value?.let { hr ->
                println("Live HR: $hr")
            }
        }
    } catch (e: Exception) {
        println("Failed to start stream: $e")
    }
}
```

### `stopStream()`

Stops the active camera session, terminates the background inference loop, and clears internal data buffers.

```kotlin
client.stopStream()
```

### `resetStream()`

Clears the internal data buffers (PPG history, face tracking state) without stopping the camera. Call this if the user moves drastically and you want to force a fresh estimation window.

```kotlin
client.resetStream()
```

### `close()`

Permanently releases resources: removes the app lifecycle observer and closes the underlying stream processor. Call this once the client is no longer needed — the JVM has no deterministic destructor to do this for you automatically.

```kotlin
client.close()
```

---

## Data Models

### `VitalLensResult`

The data structure returned by the SDK. All physiological data is represented as arrays matching the frame count, with convenient scalar accessors.

| Property | Type | Description |
| --- | --- | --- |
| `face` | `FaceData` | Bounding boxes and confidence of detected faces. |
| `vitals` | `Map<String, Vital>` | Raw map of all returned scalar signals. |
| `waveforms` | `Map<String, Waveform>` | Raw map of all returned time-series signals. |
| `time` | `List<Double>` | Array of capture timestamps for the returned data. |
| `fps` | `Double?` | The actual frames per second processed. |
| `message` | `String?` | Status message from the inference engine. |

**Convenience Accessors:**
Instead of looking up string keys in maps, you can use these strongly-typed properties:

- `heartRate: Vital?`
- `respiratoryRate: Vital?`
- `hrvSdnn: Vital?`
- `hrvRmssd: Vital?`
- `ppg: Waveform?`
- `resp: Waveform?`

### `Vital`

Represents a scalar physiological estimation.

| Property | Type | Description |
| --- | --- | --- |
| `value` | `Double` | The estimated value (e.g., `72.0`). |
| `confidence` | `Double` | Estimation confidence from `0.0` to `1.0`. |
| `unit` | `String` | The unit of measurement (e.g., `"bpm"`, `"ms"`). |

### `Waveform`

Represents a continuous signal over time.

| Property | Type | Description |
| --- | --- | --- |
| `data` | `List<Float>` | The raw signal array. |
| `confidence` | `List<Float>` | Confidence array (`0.0`-`1.0`) mapping to each point in `data`. |
| `unit` | `String?` | The unit of the signal (often unitless for PPG). |

### `FaceData`

Details about the face tracking during the measurement window.

| Property | Type | Description |
| --- | --- | --- |
| `boundingBoxes` | `List<Rect>` | Normalized (`0.0`-`1.0`) tracking boxes for each frame. |
| `confidence` | `List<Double>?` | Confidence array (`0.0`-`1.0`) of the face detection for each frame. |
