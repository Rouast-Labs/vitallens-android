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
 * [AndroidView]'s `factory` lambda runs exactly once per composable instance, so
 * [onViewAvailable] fires exactly once with no bookkeeping needed to guard against being called
 * again later. It receives a real [PreviewView] directly — [com.rouast.vitallens.camera.CameraSource]/
 * [com.rouast.vitallens.VitalLens] bind straight to it via `showPreview`/`startStream(preview:)`.
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
