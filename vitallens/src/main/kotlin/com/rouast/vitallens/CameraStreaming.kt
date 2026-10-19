package com.rouast.vitallens

import androidx.camera.view.PreviewView
import kotlinx.coroutines.flow.Flow

/** Defines the interface for continuous video frame generation, typically from a device camera. */
interface CameraStreaming {
    /** The stream of input frames including metadata. */
    val stream: Flow<InputFrame>

    /** Configures and starts the video stream. */
    suspend fun start()

    /** Stops the video stream. */
    fun stop()

    /**
     * Fixes (or releases) the camera's automatic exposure and white balance.
     *
     * Automatic adjustments change the brightness and colour of the whole image, which the pulse
     * estimation reads as signal. The default implementation does nothing, as most sources do not
     * control a camera.
     *
     * @param locked `true` to keep the current exposure and white balance, `false` to return to
     *   automatic.
     */
    fun setSettingsLocked(locked: Boolean) {}

    /**
     * Attaches a live preview of the video stream to the specified view.
     *
     * Must be called from the main thread: implementations attach the preview surface directly to
     * a live Android [android.view.View] hierarchy, which is only safe to touch from the UI
     * thread. Kotlin has no compile-time way to enforce this — callers must get it right.
     */
    fun showPreview(view: PreviewView)
}
