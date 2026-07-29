# Examples

These examples demonstrate the different ways to integrate the VitalLens SDK into your Android application, ranging from drop-in Compose screens to fully custom data pipelines.

## Compose Pre-built Screen (Easiest)

The fastest way to get started is by using `ScanScreen` from the `vitallens-ui` module. It handles camera permissions, user guidance, and the measurement timer automatically.

```kotlin
import androidx.compose.runtime.Composable
import com.rouast.vitallens.ui.ScanScreen
import com.rouast.vitallens.ui.VitalLensMode

@Composable
fun SimpleScanExample() {
    ScanScreen(
        apiKey = "YOUR_API_KEY",
        method = "vitallens",
        mode = VitalLensMode.ECO, // 15 FPS
        onComplete = { result ->
            result.heartRate?.value?.let { hr ->
                println("✅ Scan Complete! Heart Rate: $hr bpm")
            }
            result.hrvSdnn?.value?.let { hrv ->
                println("📈 HRV (SDNN): $hrv ms")
            }
        },
    )
}
```

## Custom Camera Stream

If you want to build a completely custom UI but still let the SDK manage the camera hardware, use the `VitalLens` client directly together with `CameraPreview`.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.ui.CameraPreview
import kotlinx.coroutines.launch

@Composable
fun CustomCameraExample() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { VitalLens(context = context, apiKey = "YOUR_API_KEY") }

    DisposableEffect(Unit) {
        onDispose { client.stopStream() }
    }

    // Listen for face detection events to update custom UI instructions
    client.onFaceStateChanged = { isPresent ->
        println(if (isPresent) "Face found, analyzing..." else "Please face the camera.")
    }

    CameraPreview { previewView ->
        scope.launch {
            try {
                val stream = client.startStream(preview = previewView)
                // Consume the continuous stream of results
                stream.collect { result ->
                    result.heartRate?.value?.let { hr ->
                        println("Live HR: $hr bpm")
                    }
                }
            } catch (e: Exception) {
                println("Stream error: $e")
            }
        }
    }
}
```

## Analyzing Video Files

You can process pre-recorded video files (e.g., picked from the device's photo library or bundled with your app). The SDK handles chunking, frame extraction, and API communication automatically.

```kotlin
import android.net.Uri
import com.rouast.vitallens.VitalLens

suspend fun analyzeLocalVideo(context: Context, videoUri: Uri) {
    val client = VitalLens(context = context, apiKey = "YOUR_API_KEY", method = "vitallens-2.0")

    try {
        println("Processing video file...")
        val result = client.processVideoFile(videoUri)

        println("--- Final Results ---")
        println("Avg Heart Rate:   ${result.heartRate?.value ?: 0} bpm")
        println("Respiratory Rate: ${result.respiratoryRate?.value ?: 0} rpm")
        println("HRV (SDNN):       ${result.hrvSdnn?.value ?: 0} ms")
    } catch (e: Exception) {
        println("Analysis failed: $e")
    }
}
```

## Bring Your Own Camera (`PassiveSource`)

If your app already controls its own camera pipeline (for instance, you are using CameraX for another purpose, ARCore, or custom recording), use `PassiveSource` to inject `Bitmap` frames directly into the SDK.

```kotlin
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.rouast.vitallens.VitalLens
import com.rouast.vitallens.camera.PassiveSource
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.launch

class MyExistingCameraManager(context: Context, scope: CoroutineScope) {

    private val passiveSource = PassiveSource()
    private val client = VitalLens(context = context, apiKey = "YOUR_API_KEY", source = passiveSource)

    init {
        scope.launch {
            // Start the stream. No camera hardware will be claimed.
            val stream = client.startStream()
            stream.collect { result ->
                println("Injected HR: ${result.heartRate?.value ?: 0}")
            }
        }
    }

    // Your existing ImageAnalysis.Analyzer
    val analyzer = ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
        val bitmap = imageProxy.toBitmap()
        val timestamp = imageProxy.imageInfo.timestamp / 1_000_000_000.0

        // Inject the frame into VitalLens
        passiveSource.inject(
            bitmap = bitmap,
            orientation = ImageOrientation.UP,
            isMirrored = true,
            timestamp = timestamp,
        )
        imageProxy.close()
    }
}
```
