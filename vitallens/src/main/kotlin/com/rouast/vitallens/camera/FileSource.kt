package com.rouast.vitallens.camera

import android.content.Context
import android.graphics.Bitmap
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

/**
 * Converts one YUV 4:2:0 output buffer from a buffer-mode [MediaCodec] decode (no output
 * [android.view.Surface]) into a [Bitmap] via a direct BT.601 integer YUV→RGB conversion.
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

            val y1192 = 1192 * (y - 16)
            val r = ((y1192 + 1634 * v) shr 10).coerceIn(0, 255)
            val g = ((y1192 - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
            val b = ((y1192 + 2066 * u) shr 10).coerceIn(0, 255)

            pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
    fun frames(): Flow<Bitmap> = flow {
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
                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val colorFormat = outputFormat.getIntegerOrNull(MediaFormat.KEY_COLOR_FORMAT)
                                ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                            val stride = outputFormat.getIntegerOrNull(MediaFormat.KEY_STRIDE) ?: width
                            val sliceHeight = outputFormat.getIntegerOrNull(MediaFormat.KEY_SLICE_HEIGHT) ?: height
                            emit(
                                decodeYuvBufferToBitmap(
                                    buffer = outputBuffer,
                                    offset = bufferInfo.offset,
                                    width = width,
                                    height = height,
                                    colorFormat = colorFormat,
                                    stride = stride,
                                    sliceHeight = sliceHeight,
                                )
                            )
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
