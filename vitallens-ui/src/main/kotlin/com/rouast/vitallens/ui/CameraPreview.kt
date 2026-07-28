package com.rouast.vitallens.ui

import android.graphics.Color
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A Compose wrapper providing a [PreviewView] for the camera preview.
 *
 * Mirrors Swift's `CameraPreview` (`UIViewRepresentable`), but simpler: [AndroidView]'s `factory`
 * lambda already runs exactly once per composable instance, so there's no need for Swift's
 * `Coordinator`/`hasCalledOnViewAvailable` bookkeeping to guarantee a single invocation across
 * repeated `updateUIView` calls. Also more directly typed than Swift's version — [onViewAvailable]
 * receives a real [PreviewView] rather than a raw `UIView` a caller must know to attach a capture
 * layer to; [com.rouast.vitallens.camera.CameraSource]/[com.rouast.vitallens.VitalLens] already
 * bind directly to a [PreviewView] via `showPreview`/`startStream(preview:)`.
 *
 * @param onViewAvailable Called once, as soon as the underlying [PreviewView] is created.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onViewAvailable: (PreviewView) -> Unit,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                setBackgroundColor(Color.BLACK)
            }.also(onViewAvailable)
        },
    )
}
