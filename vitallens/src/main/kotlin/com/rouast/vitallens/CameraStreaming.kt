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
     * Attaches a live preview of the video stream to the specified view.
     *
     * Must be called from the main thread — mirrors Swift's `@MainActor` isolation on this
     * method, which Kotlin has no compile-time equivalent for.
     */
    fun showPreview(view: PreviewView)
}
