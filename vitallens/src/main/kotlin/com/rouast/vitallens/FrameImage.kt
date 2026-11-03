package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.core.FramePlaneBuffer
import com.rouast.vitallens.core.YuvMatrix
import com.rouast.vitallens.image.CoreFrames
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.Closeable
import java.nio.ByteBuffer
import android.graphics.Rect as PixelBounds

/** The pixels of an [InputFrame]. */
sealed class FrameImage : Closeable {
    /** Visible size in pixels, before rotation. */
    abstract val width: Int
    abstract val height: Int

    /** The frame as an ARGB_8888 [Bitmap]. */
    abstract fun toBitmap(): Bitmap

    override fun close() {}

    /** A frame held as a [Bitmap]. */
    class FromBitmap(val bitmap: Bitmap) : FrameImage() {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun toBitmap(): Bitmap = bitmap
    }

    /**
     * A YUV 4:2:0 frame whose planes are read in place, e.g. a camera `YUV_420_888` image.
     * Valid until closed; [toBitmap] keeps working afterwards only if it was called before.
     */
    class Yuv420 internal constructor(
        internal val y: FramePlaneBuffer,
        internal val u: FramePlaneBuffer,
        internal val v: FramePlaneBuffer,
        /** Size of the planes, which may extend beyond [visible]. */
        internal val planesWidth: Int,
        internal val planesHeight: Int,
        internal val visible: PixelBounds,
        internal val matrix: YuvMatrix,
        /** The whole frame as one NV21 buffer (Y, then interleaved V and U), if laid out so. */
        internal val nv21: ByteBuffer? = null,
        private val onClose: (() -> Unit)? = null,
    ) : FrameImage() {
        override val width: Int get() = visible.width()
        override val height: Int get() = visible.height()

        private val lock = Any()
        private var closed = false
        private var cachedBitmap: Bitmap? = null

        /** Runs [block] with the planes still valid; throws if the frame is closed. */
        internal fun <T> read(block: (Yuv420) -> T): T = synchronized(lock) {
            check(!closed) { "frame is closed" }
            block(this)
        }

        override fun toBitmap(): Bitmap = synchronized(lock) {
            cachedBitmap ?: read { CoreFrames.toBitmap(it) }.also { cachedBitmap = it }
        }

        /** A copy of the visible frame in NV21 layout, owning its memory. */
        internal fun copyToNv21(): Yuv420 = read { copyNv21(it) }

        override fun close() {
            val release = synchronized(lock) {
                if (closed) null else { closed = true; onClose }
            }
            release?.invoke()
        }
    }
}

private fun address(buffer: ByteBuffer): Long =
    Pointer.nativeValue(Native.getDirectBufferPointer(buffer)) + buffer.position()

/** [len] bytes of [buffer] from [start] (relative to its position), as a new view. */
private fun ByteBuffer.range(start: Int, len: Int): ByteBuffer {
    val base = position()
    return duplicate().apply {
        clear()
        position(base + start)
        limit(base + start + len)
    }
}

/**
 * Copies the visible part of [src] into one NV21 buffer: what ML Kit takes without conversion,
 * and what the core reads as YUV 4:2:0. Rows are copied in bulk; when the chroma planes already
 * share NV21 memory (as on most devices), so is the chroma.
 */
private fun copyNv21(src: FrameImage.Yuv420): FrameImage.Yuv420 {
    // NV21 needs even sizes and offsets
    val left = src.visible.left and 1.inv()
    val top = src.visible.top and 1.inv()
    val w = src.width and 1.inv()
    val h = src.height and 1.inv()
    val ySize = w * h
    val out = ByteBuffer.allocateDirect(ySize + ySize / 2)

    val y = src.y
    if (y.pixelStride == 1 && y.rowStride == w && left == 0) {
        out.put(y.buffer.range(top * w, ySize))
    } else {
        for (r in 0 until h) out.put(y.buffer.range((top + r) * y.rowStride + left * y.pixelStride, w))
    }

    val u = src.u
    val v = src.v
    val rows = h / 2
    val cols = w / 2
    val interleavedVu = u.pixelStride == 2 && v.pixelStride == 2 && u.rowStride == v.rowStride &&
        u.buffer.isDirect && v.buffer.isDirect && address(u.buffer) == address(v.buffer) + 1
    if (interleavedVu && v.rowStride == w && left == 0) {
        // Contiguous NV21 rows: one copy, plus the last U sample the V view leaves out
        val start = (top / 2) * v.rowStride
        val total = rows * w
        val available = minOf(total, v.buffer.remaining() - start)
        out.put(v.buffer.range(start, available))
        if (available < total) out.put(u.buffer.get(u.buffer.position() + start + total - 2))
    } else if (interleavedVu) {
        val vCap = v.buffer.remaining()
        for (r in 0 until rows) {
            val start = (top / 2 + r) * v.rowStride + left
            // The V view of NV21 memory ends one byte before the last U sample
            val available = minOf(2 * cols, vCap - start)
            out.put(v.buffer.range(start, available))
            if (available < 2 * cols) out.put(u.buffer.get(u.buffer.position() + start + 2 * cols - 2))
        }
    } else {
        val ub = u.buffer
        val vb = v.buffer
        for (r in 0 until rows) {
            val uRow = ub.position() + (top / 2 + r) * u.rowStride + (left / 2) * u.pixelStride
            val vRow = vb.position() + (top / 2 + r) * v.rowStride + (left / 2) * v.pixelStride
            for (c in 0 until cols) {
                out.put(vb.get(vRow + c * v.pixelStride))
                out.put(ub.get(uRow + c * u.pixelStride))
            }
        }
    }
    out.flip()

    return FrameImage.Yuv420(
        y = FramePlaneBuffer(out.range(0, ySize), w, 1),
        u = FramePlaneBuffer(out.range(ySize + 1, ySize / 2 - 1), w, 2),
        v = FramePlaneBuffer(out.range(ySize, ySize / 2), w, 2),
        planesWidth = w,
        planesHeight = h,
        visible = PixelBounds(0, 0, w, h),
        matrix = src.matrix,
        nv21 = out,
    )
}
