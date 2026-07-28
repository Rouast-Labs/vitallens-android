package com.rouast.vitallens.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val DEFAULT_FRAME_RATE = 30.0f
private const val LOG_TAG = "FileSource"
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val IMAGE_ACQUIRE_MAX_ATTEMPTS = 50
private const val IMAGE_ACQUIRE_RETRY_DELAY_MS = 2L

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

/**
 * Packs a [ImageFormat.YUV_420_888] [Image]'s three (possibly differently-strided) planes into a
 * single interleaved NV21-layout byte array (Y plane, then interleaved V,U pairs) — a convenient
 * single buffer for [imageToBitmap]'s conversion loop to index into, rather than juggling three
 * separately-strided plane buffers there directly.
 */
private fun imageToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val nv21 = ByteArray(width * height + 2 * (width / 2) * (height / 2))

    var pos = 0
    val yBuffer = yPlane.buffer
    for (row in 0 until height) {
        yBuffer.position(row * yPlane.rowStride)
        yBuffer.get(nv21, pos, width)
        pos += width
    }

    if (vPlane.pixelStride == 2 && uPlane.pixelStride == 2) {
        // Semi-planar on many devices: U and V are actually offset views into the *same*
        // underlying interleaved buffer (already V,U,V,U,... — NV21's own order), with pixelStride
        // 2 as the tell. Reading both "separate" plane buffers independently can throw
        // "buffer is inaccessible" (observed on a real device/emulator) since they alias the same
        // native memory — bulk-copy from the V plane alone instead, which already carries both.
        val vBuffer = vPlane.buffer
        for (row in 0 until height / 2) {
            vBuffer.position(row * vPlane.rowStride)
            vBuffer.get(nv21, pos, width)
            pos += width
        }
    } else {
        // Fully planar: U and V are independent buffers, interleaved manually as V,U,V,U,...
        // Relative position()+get(), not absolute get(index): some Image-plane ByteBuffer
        // implementations only reliably support relative access — absolute indexed get() threw
        // "buffer is inaccessible" against a real device/emulator here, while the Y-plane's own
        // relative position()+bulk-get() above did not.
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                vBuffer.position(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[pos++] = vBuffer.get()
                uBuffer.position(row * uPlane.rowStride + col * uPlane.pixelStride)
                nv21[pos++] = uBuffer.get()
            }
        }
    }

    return nv21
}

/**
 * Converts a [ImageFormat.YUV_420_888] [Image] (from an [ImageReader]-backed [MediaCodec] decode
 * surface) into a [Bitmap] via a direct BT.601 integer YUV→RGB conversion.
 *
 * [YuvImage.compressToJpeg] (the "pure Kotlin/JVM... YuvImage... for... YUV→RGB conversion" tool
 * CLAUDE.md's ground rules otherwise sanction for exactly this) was tried first, but measurably
 * degraded rPPG signal quality: a real end-to-end integration test against the live API had HRV
 * SDNN drift to 85.6 against a 65.0±10.0 ground-truth tolerance (heart rate and everything else
 * stayed within tolerance) — JPEG's 4:2:0 chroma subsampling is inherent to the format regardless
 * of quality setting, adding a second, avoidable round of color-precision loss on top of the
 * source video's own existing subsampling. This conversion has no such extra loss; it's still
 * pure Kotlin/JVM math, not a native library.
 */
private fun imageToBitmap(image: Image): Bitmap {
    val width = image.width
    val height = image.height
    val nv21 = imageToNv21(image)
    val ySize = width * height
    val pixels = IntArray(ySize)

    for (row in 0 until height) {
        val uvRowOffset = ySize + (row / 2) * width
        for (col in 0 until width) {
            val y = nv21[row * width + col].toInt() and 0xFF
            val uvIndex = uvRowOffset + (col / 2) * 2
            val v = (nv21[uvIndex].toInt() and 0xFF) - 128
            val u = (nv21[uvIndex + 1].toInt() and 0xFF) - 128

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
 * Uses [MediaExtractor] + [MediaCodec] for sequential, in-order decoding — matching Swift's
 * `AVAssetReader`/`AVAssetReaderTrackOutput.copyNextSampleBuffer()`, which is also pure sequential
 * decode with no seeking at all. An earlier version used `MediaMetadataRetriever.getFrameAtTime`
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
     * The decoder writes to an [ImageReader] surface. An RGBA-format surface was tried first
     * (matching how [com.rouast.vitallens.camera.CameraSource] gets RGBA "for free" via CameraX's
     * `OUTPUT_IMAGE_FORMAT_RGBA_8888`), but unlike CameraX — which does its own GPU conversion
     * internally — a raw [MediaCodec] decode surface only outputs whatever colorspace the
     * decoder actually produces; the real device/emulator this was tested against threw
     * `UnsupportedOperationException` when the requested surface format didn't match. The
     * [ImageReader] is configured for [ImageFormat.YUV_420_888] instead (matching the decoder's
     * actual output), converted to [Bitmap] via a direct integer YUV→RGB conversion (see
     * `imageToBitmap`'s own KDoc for why this isn't `YuvImage.compressToJpeg`, CLAUDE.md's
     * otherwise-sanctioned tool for this).
     *
     * [ImageReader] delivers images via an async callback rather than synchronously right after
     * `releaseOutputBuffer` — an earlier version bridged that callback into this loop via a
     * cross-thread channel handoff, but hit a genuine race (`IllegalStateException: Image is
     * already closed` on a real device/emulator) that a same-thread design sidesteps entirely.
     * Instead, [ImageReader.acquireNextImage] is polled with a short bounded retry here, still on
     * the same thread/coroutine as the rest of the decode loop — no callback, no second thread,
     * no cross-thread lifecycle race possible. This keeps the same one-frame-at-a-time
     * backpressure a plain `flow { emit(...) }` gives for free (the loop doesn't decode/release
     * the next frame until this one has been emitted), rather than letting a fast decoder buffer
     * an entire video's worth of frames (hundreds of MB) ahead of a slower collector.
     */
    fun frames(): Flow<Bitmap> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var imageReader: ImageReader? = null

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
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)

            val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            imageReader = reader

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, reader.surface, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

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
                if (outputIndex >= 0) {
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val shouldRender = bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outputIndex, shouldRender)
                    if (shouldRender) {
                        var image: Image? = null
                        var attempt = 0
                        while (image == null && attempt < IMAGE_ACQUIRE_MAX_ATTEMPTS) {
                            image = reader.acquireNextImage()
                            if (image == null) {
                                delay(IMAGE_ACQUIRE_RETRY_DELAY_MS)
                                attempt++
                            }
                        }
                        if (image != null) {
                            emit(imageToBitmap(image))
                            image.close()
                        } else {
                            Log.e(LOG_TAG, "Timed out waiting for a decoded frame from ImageReader")
                        }
                    }
                    if (isEos) outputDone = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Matches Swift's behavior: a failure mid-read ends the stream rather than
            // propagating, since FileSource.from() already validated the file/track exist.
            Log.e(LOG_TAG, "Error reading frames", e)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            imageReader?.close()
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
                    // MediaMetadataRetriever reports rotation directly as degrees, unlike
                    // AVFoundation's preferredTransform affine matrix — no need to port Swift's
                    // calculateOrientation matrix decomposition, this reuses the already-tested
                    // rotationDegreesToOrientation from CameraSource.kt.
                    orientation = rotationDegreesToOrientation(rotation),
                    durationMs = durationMsValue,
                )
            } finally {
                retriever.release()
            }
        }
    }
}
