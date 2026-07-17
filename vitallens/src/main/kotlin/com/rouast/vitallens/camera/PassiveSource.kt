package com.rouast.vitallens.camera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import com.rouast.vitallens.CameraStreaming
import com.rouast.vitallens.InputFrame
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Frame hand-off buffer size between injection and whatever consumes [PassiveSource.stream]. */
private const val FRAME_CHANNEL_CAPACITY = 8

/**
 * A camera source that does not capture video itself, but accepts frames injected from an
 * external source. Used when integrating the SDK into an app that already manages its own
 * camera pipeline.
 */
class PassiveSource : CameraStreaming {

    // An explicit capacity, not Channel.BUFFERED: combined with a non-SUSPEND onBufferOverflow,
    // Channel.BUFFERED collapses to a hardcoded capacity of 1 (a ConflatedBufferedChannel) —
    // confirmed via decompiling kotlinx-coroutines-core's Channel() factory — which would silently
    // keep only the single latest injected frame rather than smoothing over brief backpressure.
    private val channel = Channel<InputFrame>(
        capacity = FRAME_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val stream: Flow<InputFrame> = channel.receiveAsFlow()

    /** A no-op for [PassiveSource] since it does not manage any hardware. */
    override suspend fun start() {}

    /**
     * A no-op for [PassiveSource]. To stop the stream, simply stop injecting frames — closing
     * the channel here would permanently prevent a later [inject] from succeeding, breaking
     * re-start cycles driven by the host app's own camera pipeline.
     */
    override fun stop() {}

    /**
     * Injects a frame into the SDK's processing pipeline.
     *
     * @param bitmap The frame from your custom camera or video output. Convert to [Bitmap]
     *   before calling this — e.g. via `ImageProxy.toBitmap()` if you're using CameraX yourself.
     * @param orientation The orientation of the image.
     * @param isMirrored Whether the image is horizontally mirrored.
     * @param timestamp The capture timestamp in seconds.
     */
    fun inject(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean, timestamp: Double) {
        channel.trySend(InputFrame(bitmap, orientation, isMirrored, timestamp))
    }

    /** A no-op for [PassiveSource]. You must manage your own preview layer. */
    override fun showPreview(view: PreviewView) {}
}
