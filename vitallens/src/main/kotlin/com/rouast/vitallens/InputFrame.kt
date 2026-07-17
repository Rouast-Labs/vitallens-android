package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect

/** A container for a video frame and its capture metadata. */
data class InputFrame(
    val bitmap: Bitmap,
    val orientation: ImageOrientation,
    val isMirrored: Boolean,
    val timestamp: Double,
)

/**
 * Maps a normalized rectangle from display/UI orientation space back to the raw, unrotated,
 * unmirrored coordinate space of the original sensor frame. Used by `ImageProcessor.kt` to
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
