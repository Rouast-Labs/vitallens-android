package com.rouast.vitallens.image

import android.graphics.Bitmap
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
 * Scales an ARGB image of [srcWidth]x[srcHeight] down to [target]x[target] by averaging every block
 * of source pixels (box / area filter).
 *
 * Averaging over the whole block is what lifts the pulse signal above sensor noise. Plain bilinear
 * sampling at a large downscale factor (e.g. ~300 px to 40 px) only reads about four source pixels per
 * output pixel and throws that averaging away. This matches the Python implementation and iOS
 * (vImage), and the model's training data more closely. The output is opaque.
 *
 * A source smaller than the target is repeated rather than left empty.
 */
internal fun boxDownsample(pixels: IntArray, srcWidth: Int, srcHeight: Int, target: Int): IntArray {
    val out = IntArray(target * target)
    for (oy in 0 until target) {
        val y0 = oy * srcHeight / target
        val y1 = maxOf(y0 + 1, (oy + 1) * srcHeight / target)
        for (ox in 0 until target) {
            val x0 = ox * srcWidth / target
            val x1 = maxOf(x0 + 1, (ox + 1) * srcWidth / target)
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in y0 until y1) {
                val row = y * srcWidth
                for (x in x0 until x1) {
                    val p = pixels[row + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
            }
            val n = ((y1 - y0) * (x1 - x0)).toLong()
            out[oy * target + ox] = (0xFF shl 24) or
                (((r + n / 2) / n).toInt() shl 16) or
                (((g + n / 2) / n).toInt() shl 8) or
                ((b + n / 2) / n).toInt()
        }
    }
    return out
}

/**
 * Rotates a [size]x[size] image clockwise by [degrees] (a multiple of 90), then mirrors it
 * horizontally if [mirrored]. Exact pixel moves, no resampling.
 */
internal fun rotateAndMirror(pixels: IntArray, size: Int, degrees: Int, mirrored: Boolean): IntArray {
    val turns = ((degrees / 90) % 4 + 4) % 4
    val out = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            var nx = x
            var ny = y
            when (turns) {
                1 -> { nx = size - 1 - y; ny = x }
                2 -> { nx = size - 1 - x; ny = size - 1 - y }
                3 -> { nx = y; ny = size - 1 - x }
            }
            if (mirrored) nx = size - 1 - nx
            out[ny * size + nx] = pixels[y * size + x]
        }
    }
    return out
}

/**
 * Crops, scales, rotates, and reflects video frames to a target size.
 *
 * A single pipeline serves both destinations: [process] returns a [Bitmap] usable directly for
 * local inference (`LocalInferenceBase.predict` consumes `List<Bitmap>` directly), and
 * [toRgbBytes] is the small extra step the API path needs on top of that. Every frame that
 * reaches this object is already a [Bitmap] — CameraSource/FileSource/PassiveSource all normalize
 * to Bitmap before a frame is ever buffered, since raw `Image`/YUV data isn't safe to hold onto
 * past the capture callback — so there's no separate raw-pixel-buffer path to support.
 *
 * No manual buffer allocation/reallocation is needed either: `Bitmap.createBitmap` allocates
 * exactly what it needs per call, with nothing to pre-size, reuse, or free explicitly.
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

        // Scale in the raw orientation by averaging, then rotate and mirror: the same order as iOS
        val region = IntArray(crop.width * crop.height)
        bitmap.getPixels(region, 0, crop.width, crop.x, crop.y, crop.width, crop.height)
        val scaled = boxDownsample(region, crop.width, crop.height, targetSize)
        val oriented = rotateAndMirror(scaled, targetSize, orientationToRotationDegrees(orientation), isMirrored)
        return Bitmap.createBitmap(oriented, targetSize, targetSize, Bitmap.Config.ARGB_8888)
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
