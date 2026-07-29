# Advanced Usage & Customization

The `vitallens-android` SDK is designed to be highly modular. While the pre-built Compose screens and standard `VitalLens` client handle most use cases, you can bypass them to integrate deeply into existing architectures or run custom on-device models.

## Custom Camera Injection (`PassiveSource`)

If your app already manages its own camera pipeline (e.g. CameraX for another purpose, ARCore, or custom recording), you cannot use the default `CameraSource`. Instead, use `PassiveSource` to inject frames directly into the SDK's processing pipeline.

```kotlin
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.camera.PassiveSource
import com.rouast.vitallens.inference.ImageOrientation

// 1. Initialize a PassiveSource
val passiveSource = PassiveSource()

// 2. Pass it to the VitalLens client
val client = VitalLens(
    context = context,
    apiKey = "YOUR_API_KEY",
    source = passiveSource,
)

// 3. Start the inference stream
scope.launch {
    val stream = client.startStream()
    stream.collect { result ->
        println("HR: ${result.heartRate?.value ?: 0}")
    }
}

// 4. Inject frames from your own ImageAnalysis.Analyzer
val analyzer = ImageAnalysis.Analyzer { imageProxy ->
    // Convert synchronously — android.media.Image is not safe to hold onto past this callback.
    val bitmap = imageProxy.toBitmap()
    val timestamp = imageProxy.imageInfo.timestamp / 1_000_000_000.0

    passiveSource.inject(
        bitmap = bitmap,
        orientation = ImageOrientation.UP, // Adjust based on your device orientation
        isMirrored = true, // Typically true for front-facing cameras
        timestamp = timestamp,
    )
    imageProxy.close()
}
```

## Local Inference (Custom On-Device Model)

`StreamProcessor` is entirely decoupled from the API. It relies on the `InferenceStrategy` interface. If you have your own on-device model (TFLite, ONNX Runtime, or anything else), you can run inference completely on-device without hitting the network.

To do this, subclass `LocalInferenceBase`:

```kotlin
import com.rouast.vitallens.inference.InferenceState
import com.rouast.vitallens.inference.InferenceOutcome
import com.rouast.vitallens.inference.LocalInferenceBase
import com.rouast.vitallens.inference.model.FaceData
import com.rouast.vitallens.inference.model.VitalLensResult
import com.rouast.vitallens.inference.model.Waveform
import com.rouast.vitallens.inference.network.ModelConfig

class MyLocalStrategy : LocalInferenceBase(
    config = ModelConfig(
        nInputs = 8,
        inputSize = 72,
        fpsTarget = 30.0,
        roiMethod = "face",
        supportedVitals = listOf("heart_rate", "respiratory_rate"),
    ),
) {
    override suspend fun predict(frames: List<Bitmap>, state: InferenceState?): InferenceOutcome {
        // 1. Convert Bitmaps to your model's input tensor format
        // 2. Run your model's prediction
        // 3. Package the raw model output (waveforms) into a VitalLensResult.
        // Note: Do not calculate vitals like 'heart_rate' here. The SDK's
        // internal engine derives them automatically from the raw signal.

        val mockPpg = List(frames.size) { 0.5f }
        val mockConf = List(frames.size) { 0.9f }

        val result = VitalLensResult(
            face = FaceData(),
            vitals = emptyMap(), // Leave empty; SDK will compute the scalar values
            waveforms = mapOf(
                "ppg_waveform" to Waveform(data = mockPpg, confidence = mockConf, unit = "unitless"),
            ),
            time = frames.map { System.currentTimeMillis() / 1000.0 },
        )

        // Return the result and the updated recurrent state (if applicable)
        return InferenceOutcome(result = result, newState = state)
    }
}

// Use it in the client
val client = VitalLens(
    context = context,
    strategy = MyLocalStrategy(),
)
```

## Custom API Host

`ScanScreen`/`MonitorScreen`/`FileScreen` accept an optional `baseUrl` parameter that overrides the default production API host — useful for pointing at a self-hosted proxy or a staging environment during development.

```kotlin
ScanScreen(
    apiKey = "YOUR_API_KEY",
    baseUrl = "https://your-staging-host.com/vitallens-v3".toHttpUrl(),
    onComplete = { /* ... */ },
)
```

If you're using `VitalLens` directly rather than the pre-built screens, pass a custom `strategy` instead — see [Initialization](ref.md#initialization).

## Image Processing

Under the hood, the SDK uses plain Android `Bitmap`/`Matrix` operations via the `ImageProcessor` object to crop, scale, rotate, and reflect video frames. There's no hardware-accelerated image pipeline yet — this is a deliberate, revisitable tradeoff, not an oversight: profiling has not yet shown it to be a bottleneck relative to network/inference time.

If you are writing a custom `FrameTransformer` or building your own pipeline, you can access this processor directly:

```kotlin
import com.rouast.vitallens.image.ImageProcessor

val cropped = ImageProcessor.process(
    bitmap = rawCameraBitmap,
    roi = faceRect,
    targetSize = 40,
    orientation = ImageOrientation.UP,
    isMirrored = false,
)

val rgbBytes = ImageProcessor.toRgbBytes(cropped)
```
