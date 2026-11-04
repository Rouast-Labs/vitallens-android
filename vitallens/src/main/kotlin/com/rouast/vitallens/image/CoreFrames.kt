package com.rouast.vitallens.image

import android.graphics.Bitmap
import android.media.Image
import com.rouast.vitallens.core.FramePlaneBuffer
import com.rouast.vitallens.core.Orientation
import com.rouast.vitallens.core.PixelFormat
import com.rouast.vitallens.core.PixelRect
import com.rouast.vitallens.core.YuvMatrix
import com.rouast.vitallens.core.prepareFrame
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import com.rouast.vitallens.FrameImage
import com.rouast.vitallens.mappedToRaw
import com.rouast.vitallens.vision.orientationToRotationDegrees
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns frames into model input (packed RGB, `targetSize * targetSize * 3` bytes) with
 * vitallens-core's `prepareFrame`: crop, colour conversion, area averaging, rotation and mirroring
 * in one native pass. The core reads the planes in place, so no frame is copied across the FFI.
 */
internal object CoreFrames {

    private fun plane(buffer: ByteBuffer, rowStride: Int, pixelStride: Int) =
        FramePlaneBuffer(buffer, rowStride, pixelStride)

    private fun orientationOf(orientation: ImageOrientation, isMirrored: Boolean) =
        Orientation(rotationDegrees = orientationToRotationDegrees(orientation).toUInt(), mirrored = isMirrored)

    /**
     * [roi] (normalized, display orientation) mapped to a pixel crop of a raw frame of
     * [width] x [height], offset by ([left], [top]) within the planes.
     */
    private fun pixelCrop(roi: Rect, width: Int, height: Int, left: Int, top: Int,
                          orientation: ImageOrientation, isMirrored: Boolean): PixelRect {
        val crop = computeCropRect(width, height, roi.mappedToRaw(orientation, isMirrored))
        return PixelRect(
            x = (left + crop.x).toUInt(),
            y = (top + crop.y).toUInt(),
            width = crop.width.toUInt(),
            height = crop.height.toUInt(),
        )
    }

    /**
     * Prepares a YUV 4:2:0 [image] (e.g. a [android.media.MediaCodec] output image or a camera
     * `YUV_420_888` frame) in place. The image's crop rectangle is the visible frame. [image] must
     * stay open until this returns.
     */
    fun fromImage(
        image: Image,
        roi: Rect,
        targetSize: Int,
        orientation: ImageOrientation,
        isMirrored: Boolean,
        matrix: YuvMatrix,
    ): ByteArray {
        val visible = image.cropRect
        val (y, u, v) = image.planes
        return prepareFrame(
            planes = listOf(
                plane(y.buffer, y.rowStride, y.pixelStride),
                plane(u.buffer, u.rowStride, u.pixelStride),
                plane(v.buffer, v.rowStride, v.pixelStride),
            ),
            width = image.width,
            height = image.height,
            format = PixelFormat.YUV420,
            yuvMatrix = matrix,
            crop = pixelCrop(roi, visible.width(), visible.height(), visible.left, visible.top, orientation, isMirrored),
            outWidth = targetSize,
            outHeight = targetSize,
            orientation = orientationOf(orientation, isMirrored),
        )
    }

    /**
     * Prepares a YUV 4:2:0 frame held in one decoder output [buffer] (the layout described by the
     * decoder's output format) in place.
     */
    fun fromYuvBuffer(
        buffer: ByteBuffer,
        offset: Int,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        semiPlanar: Boolean,
        roi: Rect,
        targetSize: Int,
        orientation: ImageOrientation,
        isMirrored: Boolean,
        matrix: YuvMatrix,
    ): ByteArray {
        val ySize = stride * sliceHeight
        val chromaStride = if (semiPlanar) stride else stride / 2
        val chromaPixelStride = if (semiPlanar) 2 else 1
        val uStart = offset + ySize
        val vStart = if (semiPlanar) uStart + 1 else uStart + chromaStride * (sliceHeight / 2)
        fun at(start: Int): ByteBuffer = buffer.duplicate().apply { position(start) }
        return prepareFrame(
            planes = listOf(
                plane(at(offset), stride, 1),
                plane(at(uStart), chromaStride, chromaPixelStride),
                plane(at(vStart), chromaStride, chromaPixelStride),
            ),
            width = width,
            height = height,
            format = PixelFormat.YUV420,
            yuvMatrix = matrix,
            crop = pixelCrop(roi, width, height, 0, 0, orientation, isMirrored),
            outWidth = targetSize,
            outHeight = targetSize,
            orientation = orientationOf(orientation, isMirrored),
        )
    }

    /** Prepares a YUV 4:2:0 frame in place, e.g. a camera image. */
    fun fromYuv(
        image: FrameImage.Yuv420,
        roi: Rect,
        targetSize: Int,
        orientation: ImageOrientation,
        isMirrored: Boolean,
    ): ByteArray = image.read { f ->
        prepareFrame(
            planes = listOf(f.y, f.u, f.v),
            width = f.planesWidth,
            height = f.planesHeight,
            format = PixelFormat.YUV420,
            yuvMatrix = f.matrix,
            crop = pixelCrop(roi, f.visible.width(), f.visible.height(), f.visible.left, f.visible.top, orientation, isMirrored),
            outWidth = targetSize,
            outHeight = targetSize,
            orientation = orientationOf(orientation, isMirrored),
        )
    }

    /** Prepares any [FrameImage]. */
    fun fromFrame(image: FrameImage, roi: Rect, targetSize: Int, orientation: ImageOrientation, isMirrored: Boolean): ByteArray =
        when (image) {
            is FrameImage.FromBitmap -> fromBitmap(image.bitmap, roi, targetSize, orientation, isMirrored)
            is FrameImage.Yuv420 -> fromYuv(image, roi, targetSize, orientation, isMirrored)
        }

    /** Converts the visible part of a YUV frame to an ARGB_8888 [Bitmap], unrotated. The planes must be valid. */
    fun toBitmap(f: FrameImage.Yuv420): Bitmap {
        val w = f.visible.width()
        val h = f.visible.height()
        val rgb = prepareFrame(
            planes = listOf(f.y, f.u, f.v),
            width = f.planesWidth,
            height = f.planesHeight,
            format = PixelFormat.YUV420,
            yuvMatrix = f.matrix,
            crop = PixelRect(f.visible.left.toUInt(), f.visible.top.toUInt(), w.toUInt(), h.toUInt()),
            outWidth = w,
            outHeight = h,
            orientation = Orientation(0u, false),
        )
        return rgbToBitmap(rgb, w, h)
    }

    /** Packed RGB bytes as an opaque ARGB_8888 [Bitmap]. */
    fun rgbToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val j = i * 3
            argb[i] = (0xFF shl 24) or ((rgb[j].toInt() and 0xFF) shl 16) or
                ((rgb[j + 1].toInt() and 0xFF) shl 8) or (rgb[j + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Reused per thread: the crop's ARGB pixels, and a direct buffer holding them as bytes. */
    private class Scratch {
        var pixels = IntArray(0)
        var bytes: ByteBuffer = ByteBuffer.allocateDirect(0)

        fun ensure(n: Int) {
            if (pixels.size < n) pixels = IntArray(n)
            if (bytes.capacity() < n * 4) bytes = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Prepares an ARGB_8888 [bitmap]. Only the crop's pixels are copied out of the bitmap (Android
     * gives no direct access to its memory without the NDK); the core then reads that copy in place.
     */
    fun fromBitmap(
        bitmap: Bitmap,
        roi: Rect,
        targetSize: Int,
        orientation: ImageOrientation,
        isMirrored: Boolean,
    ): ByteArray {
        val crop = computeCropRect(bitmap.width, bitmap.height, roi.mappedToRaw(orientation, isMirrored))
        val n = crop.width * crop.height
        val s = scratch.get()!!
        s.ensure(n)
        bitmap.getPixels(s.pixels, 0, crop.width, crop.x, crop.y, crop.width, crop.height)
        // An ARGB Int written little-endian is the byte sequence B, G, R, A.
        s.bytes.clear()
        s.bytes.asIntBuffer().put(s.pixels, 0, n)
        s.bytes.limit(n * 4)
        return prepareFrame(
            planes = listOf(plane(s.bytes, crop.width * 4, 4)),
            width = crop.width,
            height = crop.height,
            format = PixelFormat.BGRA,
            yuvMatrix = YuvMatrix.BT601_FULL,
            crop = PixelRect(0u, 0u, crop.width.toUInt(), crop.height.toUInt()),
            outWidth = targetSize,
            outHeight = targetSize,
            orientation = orientationOf(orientation, isMirrored),
        )
    }
}
