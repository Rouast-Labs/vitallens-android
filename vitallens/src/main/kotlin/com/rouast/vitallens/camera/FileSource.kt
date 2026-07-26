package com.rouast.vitallens.camera

import android.content.Context
import android.graphics.Bitmap
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

private const val DEFAULT_FRAME_RATE = 30.0f
private const val LOG_TAG = "FileSource"

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
 * A helper class to read video frames from a local file [Uri] using [MediaMetadataRetriever].
 *
 * Uses `MediaMetadataRetriever.getFrameAtTime` (the "start simple" choice flagged in CLAUDE.md)
 * rather than `MediaExtractor`/`MediaCodec` — simpler, but each frame extraction independently
 * seeks and decodes, which is slower than sequential decode. Revisit if this proves to be a
 * real-world bottleneck.
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
     * Reads frames sequentially by seeking to successive timestamps spaced by
     * `1 / nominalFrameRate`.
     *
     * Uses [MediaMetadataRetriever.OPTION_CLOSEST], deliberately not `OPTION_CLOSEST_SYNC`:
     * sync/keyframe-only extraction would return the same frame repeated across an entire GOP
     * (often 1-2 seconds), which would silently defeat this SDK's purpose — detecting subtle
     * frame-to-frame color change requires the actual nearest frame, not the nearest keyframe.
     */
    fun frames(): Flow<Bitmap> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frameIntervalUs = (1_000_000.0 / nominalFrameRate).toLong()
            val durationUs = durationMs * 1000
            var timeUs = 0L
            while (timeUs < durationUs) {
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                emit(bitmap)
                timeUs += frameIntervalUs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Matches Swift's behavior: a failure mid-read ends the stream rather than
            // propagating, since FileSource.from() already validated the file/track exist.
            Log.e(LOG_TAG, "Error reading frames", e)
        } finally {
            retriever.release()
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
