package com.rouast.vitallens.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.VitalLensException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private const val DEFAULT_FRAME_RATE = 30.0f
private const val LOG_TAG = "FileSource"
private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Parses a nominal frame rate from whatever `MediaMetadataRetriever` metadata is actually
 * available, falling back to [DEFAULT_FRAME_RATE].
 *
 * Unlike `AVAssetReaderTrack`, which always reports an exact `nominalFrameRate` from the
 * container's track metadata, Android's closest analog
 * ([MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE]) is really a slow-motion-capture hint
 * and is populated inconsistently for regular video. [MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT]
 * (API 28+) combined with duration is a more reliable fallback where available, but this SDK's
 * minSdk is 26.
 */
internal fun parseFrameRate(captureFrameRate: String?, frameCount: String?, durationMs: String?): Float {
    captureFrameRate?.toFloatOrNull()?.let { if (it > 0f) return it }
    val count = frameCount?.toFloatOrNull()
    val duration = durationMs?.toFloatOrNull()
    if (count != null && count > 0f && duration != null && duration > 0f) {
        return count / (duration / 1000f)
    }
    return DEFAULT_FRAME_RATE
}

private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

/** BT.601 integer YUV→RGB conversion of one pixel; [u] and [v] are already centred on zero. */
private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val y1192 = 1192 * (y - 16)
    val r = ((y1192 + 1634 * v) shr 10).coerceIn(0, 255)
    val g = ((y1192 - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
    val b = ((y1192 + 2066 * u) shr 10).coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Converts YUV 4:2:0 planes into ARGB pixels, reading each plane through its own row and pixel
 * stride, as described by [Image.Plane]. This covers planar chroma (pixel stride 1, separate U and
 * V planes) and semi-planar chroma (pixel stride 2, U and V interleaved) alike, so it does not
 * depend on which layout a decoder chose for a [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible]
 * request. The U and V planes share [uvRowStride] and [uvPixelStride], as `YUV_420_888` guarantees.
 * [left]/[top] offset into the planes for a crop rectangle.
 */
internal fun yuvPlanesToArgb(
    y: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    u: ByteBuffer,
    v: ByteBuffer,
    uvRowStride: Int,
    uvPixelStride: Int,
    width: Int,
    height: Int,
    left: Int = 0,
    top: Int = 0,
): IntArray {
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val yRowStart = (top + row) * yRowStride
        val uvRowStart = ((top + row) / 2) * uvRowStride
        for (col in 0 until width) {
            val luma = y.get(yRowStart + (left + col) * yPixelStride).toInt() and 0xFF
            val uvIndex = uvRowStart + ((left + col) / 2) * uvPixelStride
            val cb = (u.get(uvIndex).toInt() and 0xFF) - 128
            val cr = (v.get(uvIndex).toInt() and 0xFF) - 128
            pixels[row * width + col] = yuvToArgb(luma, cb, cr)
        }
    }
    return pixels
}

/** Converts a decoder output [Image] (`YUV_420_888`) into a [Bitmap] of its crop rectangle. */
private fun decodeImageToBitmap(image: Image): Bitmap {
    val crop = image.cropRect
    val (yPlane, uPlane, vPlane) = image.planes
    val pixels = yuvPlanesToArgb(
        y = yPlane.buffer,
        yRowStride = yPlane.rowStride,
        yPixelStride = yPlane.pixelStride,
        u = uPlane.buffer,
        v = vPlane.buffer,
        uvRowStride = uPlane.rowStride,
        uvPixelStride = uPlane.pixelStride,
        width = crop.width(),
        height = crop.height(),
        left = crop.left,
        top = crop.top,
    )
    return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
}

/**
 * Converts one YUV 4:2:0 output buffer from a buffer-mode [MediaCodec] decode (no output
 * [android.view.Surface]) into a [Bitmap] via a direct BT.601 integer YUV→RGB conversion.
 *
 * Fallback only: [FileSource.frames] reads the decoder's own plane layout through
 * [MediaCodec.getOutputImage] and uses [decodeImageToBitmap]. This function has to guess the
 * layout from the reported color format, and a decoder can report
 * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible] while writing planar data (the
 * emulator's `c2.goldfish.h264.decoder` does), which the guess below reads as semi-planar and
 * so mixes up the chroma.
 *
 * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible], requested at configure time, is
 * one of two concrete layouts here: fully planar (Y, then U, then V, each its own contiguous
 * plane — [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar]/`PackedPlanar`) or
 * semi-planar (Y, then a single interleaved Cb/Cr plane, Cb first —
 * `COLOR_FormatYUV420SemiPlanar`/`PackedSemiPlanar`, i.e. NV12's byte order). Any other reported
 * value is treated as semi-planar, the overwhelmingly common concrete choice real hardware
 * decoders make for a flexible request. [stride]/[sliceHeight] come from the decoder's own output
 * [MediaFormat] (`KEY_STRIDE`/`KEY_SLICE_HEIGHT`), not [width]/[height] — codecs commonly pad the
 * coded buffer to a macroblock-aligned size (e.g. 1080 rounds up to a slice height of 1088), and
 * the row/plane math here needs the real padded layout to index correctly.
 *
 * This buffer-mode path replaces an earlier version that decoded onto a [android.view.Surface]
 * backed by an [android.media.ImageReader] configured for `ImageFormat.YUV_420_888`, manually
 * walking `Image.getPlanes()`. That let the decoder pick its own output buffer layout for display
 * efficiency — on a real Samsung Exynos device, the returned `Image` reported a Y-plane row
 * stride of 4x the frame width (not a padding artifact — every read the old code performed was
 * within the reported buffer capacity, yet it still crashed), evidence of a vendor-private/tiled
 * layout that `Image.Plane`'s stride/pixel-stride metadata didn't describe correctly. The result
 * was a native `SIGSEGV` reading past the plane `ByteBuffer`'s actual mapped memory — unrecoverable
 * from Kotlin, since no `try`/`catch` stops a native crash. Buffer-mode output has no such
 * escape hatch for the vendor: [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible] is a
 * CDD-mandated format every AOSP-compliant decoder (hardware or software) has been required to
 * support since API 21, specifically so callers aren't exposed to private buffer layouts — the
 * same guarantee `vitallens-ios`'s `AVAssetReaderTrackOutput` leans on by requesting
 * `kCVPixelFormatType_32BGRA` explicitly rather than trusting whatever the decoder produces.
 */
private fun decodeYuvBufferToBitmap(
    buffer: ByteBuffer,
    offset: Int,
    width: Int,
    height: Int,
    colorFormat: Int,
    stride: Int,
    sliceHeight: Int,
): Bitmap {
    val rowStride = if (stride > 0) stride else width
    val ySliceHeight = if (sliceHeight > 0) sliceHeight else height
    val semiPlanar = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar &&
        colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar

    val ySize = rowStride * ySliceHeight
    val chromaRowStride = if (semiPlanar) rowStride else rowStride / 2
    val uStart = offset + ySize
    val vStart = if (semiPlanar) uStart else uStart + chromaRowStride * (ySliceHeight / 2)

    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val yRowStart = offset + row * rowStride
        val chromaRow = row / 2
        for (col in 0 until width) {
            val y = buffer.get(yRowStart + col).toInt() and 0xFF
            val u: Int
            val v: Int
            if (semiPlanar) {
                val uvIndex = uStart + chromaRow * chromaRowStride + (col / 2) * 2
                u = (buffer.get(uvIndex).toInt() and 0xFF) - 128
                v = (buffer.get(uvIndex + 1).toInt() and 0xFF) - 128
            } else {
                val chromaCol = col / 2
                u = (buffer.get(uStart + chromaRow * chromaRowStride + chromaCol).toInt() and 0xFF) - 128
                v = (buffer.get(vStart + chromaRow * chromaRowStride + chromaCol).toInt() and 0xFF) - 128
            }

            pixels[row * width + col] = yuvToArgb(y, u, v)
        }
    }

    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * One decoded video frame, valid only inside the [FileSource.mapFrames] transform that receives
 * it: the decoder reuses the memory as soon as the transform returns.
 */
sealed class DecodedFrame {
    /** The visible frame size. */
    abstract val width: Int
    abstract val height: Int

    /** YUV to RGB matrix for this video, from the decoder's colour standard and range. */
    abstract val yuvMatrix: com.rouast.vitallens.core.YuvMatrix

    /** Converts the whole frame to a [Bitmap] (slow: a per-pixel conversion in Kotlin). */
    abstract fun toBitmap(): Bitmap

    /** A frame read through [MediaCodec.getOutputImage], with the decoder's own plane layout. */
    class FromImage internal constructor(
        val image: Image,
        override val yuvMatrix: com.rouast.vitallens.core.YuvMatrix,
    ) : DecodedFrame() {
        override val width: Int get() = image.cropRect.width()
        override val height: Int get() = image.cropRect.height()
        override fun toBitmap(): Bitmap = decodeImageToBitmap(image)

        /** The image as a [com.rouast.vitallens.FrameImage], read in place while [image] is open. */
        internal fun toFrameImage(): com.rouast.vitallens.FrameImage.Yuv420 {
            val (y, u, v) = image.planes
            return com.rouast.vitallens.FrameImage.Yuv420(
                y = com.rouast.vitallens.core.FramePlaneBuffer(y.buffer, y.rowStride, y.pixelStride),
                u = com.rouast.vitallens.core.FramePlaneBuffer(u.buffer, u.rowStride, u.pixelStride),
                v = com.rouast.vitallens.core.FramePlaneBuffer(v.buffer, v.rowStride, v.pixelStride),
                planesWidth = image.width,
                planesHeight = image.height,
                visible = image.cropRect,
                matrix = yuvMatrix,
            )
        }
    }

    /** A frame in a raw output buffer, laid out as the decoder's output format describes. */
    class FromBuffer internal constructor(
        val buffer: ByteBuffer,
        val offset: Int,
        override val width: Int,
        override val height: Int,
        val colorFormat: Int,
        val stride: Int,
        val sliceHeight: Int,
        override val yuvMatrix: com.rouast.vitallens.core.YuvMatrix,
    ) : DecodedFrame() {
        val rowStride: Int get() = if (stride > 0) stride else width
        val ySliceHeight: Int get() = if (sliceHeight > 0) sliceHeight else height
        val semiPlanar: Boolean
            get() = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar &&
                colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar

        override fun toBitmap(): Bitmap =
            decodeYuvBufferToBitmap(buffer, offset, width, height, colorFormat, stride, sliceHeight)
    }
}

/** The YUV matrix a decoder output format declares; BT.601 limited range when it says nothing. */
private fun yuvMatrixOf(format: MediaFormat): com.rouast.vitallens.core.YuvMatrix {
    val bt709 = format.getIntegerOrNull(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
    val full = format.getIntegerOrNull(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
    return when {
        bt709 && full -> com.rouast.vitallens.core.YuvMatrix.BT709_FULL
        bt709 -> com.rouast.vitallens.core.YuvMatrix.BT709_LIMITED
        full -> com.rouast.vitallens.core.YuvMatrix.BT601_FULL
        else -> com.rouast.vitallens.core.YuvMatrix.BT601_LIMITED
    }
}

/**
 * A helper class to read video frames from a local file [Uri].
 *
 * Uses [MediaExtractor] + [MediaCodec] for sequential, in-order decoding — pure sequential decode
 * with no seeking at all. An earlier version used `MediaMetadataRetriever.getFrameAtTime`
 * (the "start simple" choice originally flagged in CLAUDE.md) — simpler, but each frame
 * extraction independently sought and decoded, which measured at ~265ms/frame against a real
 * 630-frame video on a real device (97.8% of a real end-to-end file-processing integration test's
 * runtime was pure decode overhead, confirmed by profiling before this rewrite) — an actual
 * real-world bottleneck, not just a theoretical one.
 */
class FileSource private constructor(
    private val context: Context,
    private val uri: Uri,
    val naturalSize: Size,
    val nominalFrameRate: Float,
    val orientation: ImageOrientation,
    private val durationMs: Long,
) {

    /**
     * Reads frames sequentially via hardware-accelerated decode, in container order.
     *
     * Buffer-mode decode (`decoder.configure(format, /* surface = */ null, null, 0)`, requesting
     * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible]) — see [decodeYuvBufferToBitmap]'s
     * own KDoc for why this replaced an earlier `Surface`/`ImageReader` version. This also drops the
     * async image-acquire retry loop that version needed: [MediaCodec.getOutputBuffer] returns
     * synchronously right after [MediaCodec.dequeueOutputBuffer], no producer/consumer handoff to
     * an [android.media.ImageReader] involved.
     */
    fun frames(): Flow<Bitmap> = mapFrames { frame, _ -> frame.toBitmap() }

    /**
     * Decodes every frame and emits what [transform] returns for it (nothing for `null`).
     * [transform] receives the frame and its index and runs while the frame's memory is valid,
     * so it can read the decoder output in place, e.g. hand the planes to vitallens-core.
     */
    fun <T : Any> mapFrames(transform: (DecodedFrame, Int) -> T?): Flow<T> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (trackIndex == null) {
                Log.e(LOG_TAG, "No video track found for decoding")
                return@flow
            }

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var width = format.getInteger(MediaFormat.KEY_WIDTH)
            var height = format.getInteger(MediaFormat.KEY_HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormat = decoder.outputFormat
            var warnedNoImage = false
            var index = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = decoder.outputFormat
                    width = outputFormat.getIntegerOrNull(MediaFormat.KEY_WIDTH) ?: width
                    height = outputFormat.getIntegerOrNull(MediaFormat.KEY_HEIGHT) ?: height
                } else if (outputIndex >= 0) {
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val image = if (bufferInfo.size > 0) runCatching { decoder.getOutputImage(outputIndex) }.getOrNull() else null
                    if (image != null) {
                        val result = try {
                            transform(DecodedFrame.FromImage(image, yuvMatrixOf(outputFormat)), index++)
                        } finally {
                            image.close()
                        }
                        if (result != null) emit(result)
                    } else if (bufferInfo.size > 0) {
                        if (!warnedNoImage) {
                            Log.w(LOG_TAG, "Decoder provides no output Image, guessing the YUV layout from the color format")
                            warnedNoImage = true
                        }
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val colorFormat = outputFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_FORMAT)
                                ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                            val stride = outputFormat.getIntegerOrNull(MediaFormat.KEY_STRIDE) ?: width
                            val sliceHeight = outputFormat.getIntegerOrNull(MediaFormat.KEY_SLICE_HEIGHT) ?: height
                            val frame = DecodedFrame.FromBuffer(
                                buffer = outputBuffer,
                                offset = bufferInfo.offset,
                                width = width,
                                height = height,
                                colorFormat = colorFormat,
                                stride = stride,
                                sliceHeight = sliceHeight,
                                yuvMatrix = yuvMatrixOf(outputFormat),
                            )
                            transform(frame, index++)?.let { emit(it) }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (isEos) outputDone = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure mid-read ends the stream rather than propagating, since FileSource.from()
            // already validated the file/track exist — by this point we're mid-decode, and a
            // partial result (whatever frames were already emitted) is more useful to the caller
            // than an exception that discards everything decoded so far.
            Log.e(LOG_TAG, "Error reading frames", e)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractor.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /**
         * Creates a [FileSource] asynchronously from a local file [Uri].
         *
         * @throws VitalLensException.ProcessingError if the file cannot be read or contains no
         *   video track.
         */
        suspend fun from(context: Context, uri: Uri): FileSource = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                retriever.release()
                throw VitalLensException.ProcessingError("No video track found in file.")
            }

            try {
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideo != "yes") {
                    throw VitalLensException.ProcessingError("No video track found in file.")
                }

                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val durationMsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val frameCount = if (Build.VERSION.SDK_INT >= 28) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                } else {
                    null
                }
                val frameRate = parseFrameRate(
                    captureFrameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
                    frameCount = frameCount,
                    durationMs = durationMsValue.toString(),
                )

                FileSource(
                    context = context,
                    uri = uri,
                    naturalSize = Size(width, height),
                    nominalFrameRate = frameRate,
                    // MediaMetadataRetriever reports rotation directly as degrees, so this can
                    // reuse the already-tested rotationDegreesToOrientation from CameraSource.kt
                    // rather than needing any separate matrix decomposition.
                    orientation = rotationDegreesToOrientation(rotation),
                    durationMs = durationMsValue,
                )
            } finally {
                retriever.release()
            }
        }
    }
}
