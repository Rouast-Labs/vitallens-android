package com.rouast.vitallens.image

import android.graphics.Bitmap
import android.graphics.Matrix
import com.rouast.vitallens.mappedToRaw
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.inference.VitalLensException
import com.rouast.vitallens.vision.orientationToRotationDegrees

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
 * Crops, scales, rotates, and reflects video frames to a target size, mirroring Swift's
 * ImageProcessor.
 *
 * Unified relative to Swift's source, which has two near-duplicate pipelines (`process`
 * producing flattened RGB `Data` for the API, `processToPixelBuffer` producing a `CVPixelBuffer`
 * for local CoreML inference) branching on raw YUV-vs-BGRA `CVPixelBuffer` input. Neither
 * distinction applies here: every frame that reaches this object is already a [Bitmap] —
 * CameraSource/FileSource/PassiveSource all normalize to Bitmap before a frame is ever buffered
 * (raw `Image`/YUV isn't safe to hold onto past the capture callback) — and
 * `LocalInferenceBase.predict` already consumes `List<Bitmap>` directly, so there's no separate
 * "pixel buffer" output shape to produce. [process] returns a [Bitmap] usable directly for local
 * inference; [toRgbBytes] is the small extra step the API path needs on top of that.
 *
 * No manual buffer allocation/reallocation either: Swift's vImage buffers are raw malloc'd memory
 * needing explicit lifecycle management (allocateBuffers/freeBuffers/checkAndReallocate).
 * `Bitmap.createBitmap` allocates what it needs per call — nothing to pre-size or free.
 *
 * `debugMode`/`lastProcessedCGImage` are dropped entirely: they exist in Swift only because
 * vImage's raw ARGB buffers aren't directly viewable without conversion to `CGImage`. Our output
 * is already a `Bitmap` — already viewable/debuggable — so there's nothing to convert.
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
    ): Bitmap {
        val rawRoi = roi.mappedToRaw(orientation, isMirrored)
        val crop = computeCropRect(bitmap.width, bitmap.height, rawRoi)

        val matrix = Matrix().apply {
            postScale(targetSize.toFloat() / crop.width, targetSize.toFloat() / crop.height)
            postRotate(orientationToRotationDegrees(orientation).toFloat(), targetSize / 2f, targetSize / 2f)
            if (isMirrored) postScale(-1f, 1f, targetSize / 2f, targetSize / 2f)
        }

        return Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height, matrix, true)
    }

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
