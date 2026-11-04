package com.rouast.vitallens.image

import android.graphics.Bitmap
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException

/** The pixel-space crop rectangle a normalized [Rect] ROI maps to within a bitmap. */
internal data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** @throws VitalLensException.ProcessingError if [roi] falls outside the bitmap's bounds. */
internal fun computeCropRect(bitmapWidth: Int, bitmapHeight: Int, roi: Rect): CropRect {
    val x = (roi.x * bitmapWidth).toInt()
    val y = (roi.y * bitmapHeight).toInt()
    val width = (roi.width * bitmapWidth).toInt()
    val height = (roi.height * bitmapHeight).toInt()

    if (x < 0 || y < 0 || x + width > bitmapWidth || y + height > bitmapHeight) {
        throw VitalLensException.ProcessingError("ROI out of bounds")
    }

    return CropRect(x, y, width, height)
}

/**
 * Crops, scales, rotates, and reflects video frames to a target size, for custom pipelines and
 * [com.rouast.vitallens.FrameTransformer]s that work with [Bitmap]s. It uses the same
 * vitallens-core frame preparation as the SDK's own frame paths: area averaging, then rotation
 * and mirroring.
 */
object ImageProcessor {

    /**
     * Crops [bitmap] to [roi] (normalized 0.0-1.0, in display/upright orientation space — mapped
     * back to raw sensor space internally), scales to [targetSize]x[targetSize], and applies the
     * rotation/mirroring needed to correct [orientation]/[isMirrored].
     *
     * @throws VitalLensException.ProcessingError if [roi] falls outside the raw frame's bounds.
     */
    fun process(
        bitmap: Bitmap,
        roi: Rect,
        targetSize: Int,
        orientation: ImageOrientation,
        isMirrored: Boolean,
    ): Bitmap = CoreFrames.rgbToBitmap(
        CoreFrames.fromBitmap(bitmap, roi, targetSize, orientation, isMirrored), targetSize, targetSize,
    )

    /** Flattens [bitmap] to packed RGB bytes (no alpha channel), ready for network transmission. */
    fun toRgbBytes(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val bytes = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            bytes[i * 3] = ((pixel shr 16) and 0xFF).toByte()
            bytes[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
            bytes[i * 3 + 2] = (pixel and 0xFF).toByte()
        }
        return bytes
    }
}
