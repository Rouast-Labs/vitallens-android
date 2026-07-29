# Compose Screens

The `vitallens-ui` module provides pre-built Jetpack Compose screens to get you up and running quickly. These screens handle camera permissions, state management, user guidance, and data visualization.

## Setup

Import the screens from the UI module:

```kotlin
import com.rouast.vitallens.ui.ScanScreen
import com.rouast.vitallens.ui.MonitorScreen
import com.rouast.vitallens.ui.FileScreen
```

---

## 1. ScanScreen

A guided flow that handles a full measurement cycle. It instructs the user to position their face, checks lighting conditions, performs a ~30-second scan, and displays the final results.

```kotlin
ScanScreen(
    apiKey = "YOUR_KEY",
    method = "vitallens",
    mode = VitalLensMode.ECO,
    onComplete = { result ->
        result.heartRate?.value?.let { hr ->
            println("Final Heart Rate: $hr bpm")
        }
    },
)
```

### Parameters

| Parameter | Type | Description |
| --- | --- | --- |
| `onComplete` | `(VitalLensResult) -> Unit` | Callback triggered when the scan finishes successfully. |
| `apiKey` | `String?` | Your API Key. Required if `proxyUrl` is not set. |
| `proxyUrl` | `HttpUrl?` | URL to your backend proxy. |
| `method` | `String` | Model version (e.g., `"vitallens"`, `"vitallens-2.0"`). Default is `"vitallens"`. |
| `mode` | `VitalLensMode` | `ECO` (15 FPS, default) or `STANDARD` (30 FPS). |
| `baseUrl` | `HttpUrl?` | Overrides the API host (e.g. for a staging/dev environment). Rarely needed — see [Advanced Use](advanced.md). |

---

## 2. MonitorScreen

A dashboard that visualizes live signals continuously. It renders real-time PPG and respiratory charts, displaying numeric values as they update.

```kotlin
MonitorScreen(
    apiKey = "YOUR_KEY",
    showWaveforms = true,
)
```

### Parameters

| Parameter | Type | Description |
| --- | --- | --- |
| `apiKey` | `String?` | Your API Key. Required if `proxyUrl` is not set. |
| `proxyUrl` | `HttpUrl?` | URL to your backend proxy. |
| `method` | `String` | Model version. Default is `"vitallens"`. |
| `showWaveforms` | `Boolean` | Whether to render the real-time waveform charts. Default is `true`. |
| `initialMode` | `VitalLensMode` | Initial FPS mode `ECO` or `STANDARD`. Default is `ECO`. |
| `bufferOffsetSeconds` | `Double` | Delay in seconds for smooth chart rendering. Default is `0.15`. |
| `windowSizeSeconds` | `Double` | Duration of data to show in the waveform charts. Default is `8.0`. |
| `minDisplayDurationSeconds` | `Double` | Minimum data required before displaying values. Default is `6.0`. |
| `baseUrl` | `HttpUrl?` | Overrides the API host. Rarely needed — see [Advanced Use](advanced.md). |

---

## 3. FileScreen

A complete UI for selecting and analyzing pre-recorded videos from the device's photo library or file browser. It handles file selection, extraction, processing, and result visualization.

```kotlin
FileScreen(
    apiKey = "YOUR_KEY",
)
```

### Parameters

| Parameter | Type | Description |
| --- | --- | --- |
| `apiKey` | `String?` | Your API Key. Required if `proxyUrl` is not set. |
| `proxyUrl` | `HttpUrl?` | URL to your backend proxy. |
| `method` | `String` | Model version. Default is `"vitallens"`. |
| `baseUrl` | `HttpUrl?` | Overrides the API host. Rarely needed — see [Advanced Use](advanced.md). |
