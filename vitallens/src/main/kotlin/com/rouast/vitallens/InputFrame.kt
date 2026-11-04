package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import java.io.Closeable

/**
 * A container for a video frame and its capture metadata.
 *
 * Frames from [com.rouast.vitallens.camera.CameraSource] hold the camera's YUV planes in place
 * rather than a [Bitmap]; [bitmap] converts them on first use. Such a frame is only valid until it
 * is closed: [StreamProcessor] closes every frame it receives once it has processed it. Closing a
 * frame made from a [Bitmap] does nothing.
 */
class InputFrame internal constructor(
    /** The frame's pixels. */
    val image: FrameImage,
    val orientation: ImageOrientation,
    val isMirrored: Boolean,
    val timestamp: Double,
) : Closeable {

    constructor(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean, timestamp: Double) :
        this(FrameImage.FromBitmap(bitmap), orientation, isMirrored, timestamp)

    /** The frame as an ARGB_8888 [Bitmap]: the original one, or converted from YUV on first use. */
    val bitmap: Bitmap get() = image.toBitmap()

    /** Releases the frame's pixel memory back to its source (e.g. the camera). */
    override fun close() = image.close()
}

/**
 * Maps a normalized rectangle from display/UI orientation space back to the raw, unrotated,
 * unmirrored coordinate space of the original sensor frame. Used by `CoreFrames.kt` to
 * locate an ROI (computed against a possibly-rotated preview) within the raw frame buffer
 * before pixel extraction.
 */
fun Rect.mappedToRaw(orientation: ImageOrientation, isMirrored: Boolean): Rect {
    var rect = this
    if (isMirrored) {
        rect = Rect(x = 1.0f - rect.x - rect.width, y = rect.y, width = rect.width, height = rect.height)
    }
    rect = when (orientation) {
        ImageOrientation.LEFT, ImageOrientation.LEFT_MIRRORED ->
            Rect(x = 1.0f - rect.y - rect.height, y = rect.x, width = rect.height, height = rect.width)
        ImageOrientation.DOWN, ImageOrientation.DOWN_MIRRORED ->
            Rect(x = 1.0f - rect.x - rect.width, y = 1.0f - rect.y - rect.height, width = rect.width, height = rect.height)
        ImageOrientation.RIGHT, ImageOrientation.RIGHT_MIRRORED ->
            Rect(x = rect.y, y = 1.0f - rect.x - rect.width, width = rect.height, height = rect.width)
        else -> rect
    }
    return rect
}
