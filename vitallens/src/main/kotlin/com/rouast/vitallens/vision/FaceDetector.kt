package com.rouast.vitallens.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rouast.vitallens.FaceDetecting
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Converts our orientation enum to the clockwise degrees [InputImage.fromBitmap] expects — the
 * inverse of `CameraSource.kt`'s `rotationDegreesToOrientation`.
 */
internal fun orientationToRotationDegrees(orientation: ImageOrientation): Int = when (orientation) {
    ImageOrientation.UP, ImageOrientation.UP_MIRRORED -> 0
    ImageOrientation.RIGHT, ImageOrientation.RIGHT_MIRRORED -> 90
    ImageOrientation.DOWN, ImageOrientation.DOWN_MIRRORED -> 180
    ImageOrientation.LEFT, ImageOrientation.LEFT_MIRRORED -> 270
}

/**
 * Normalizes an ML Kit face bounding box (pixel coordinates, top-left origin, relative to the
 * already rotation-corrected [InputImage]) to 0.0-1.0 and applies the mirroring flip.
 *
 * Unlike Vision's `boundingBox` (bottom-left origin, needing a Y-flip), ML Kit already reports
 * top-left coordinates — so this needs only the mirroring adjustment Swift's
 * `convertVisionToTopLeft` also applies, not an origin conversion too.
 */
internal fun normalizeFaceBox(
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    imageWidth: Int,
    imageHeight: Int,
    isMirrored: Boolean,
): Rect {
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()
    val normalizedX = left / w
    val normalizedY = top / h
    val normalizedWidth = width / w
    val normalizedHeight = height / h
    val x = if (isMirrored) 1.0f - normalizedX - normalizedWidth else normalizedX
    return Rect(x = x, y = normalizedY, width = normalizedWidth, height = normalizedHeight)
}

/**
 * Detects faces in video frames using ML Kit's Face Detection API.
 *
 * [close] is a Kotlin-only addition with no Swift equivalent: ML Kit's `FaceDetector` client
 * holds native resources and implements `Closeable`, unlike Vision's
 * `VNDetectFaceRectanglesRequest`, which needs no explicit cleanup — same category of divergence
 * as `BufferManager.close()`.
 */
class FaceDetector : FaceDetecting, Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )

    /**
     * Detects the most prominent face in the provided frame.
     *
     * @return The bounding box of the face in normalized coordinates (0.0-1.0) with a top-left
     *   origin, or `null` if no face is found.
     */
    override suspend fun detectFace(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean): Rect? {
        val inputImage = InputImage.fromBitmap(bitmap, orientationToRotationDegrees(orientation))
        val face = detector.process(inputImage).await().firstOrNull() ?: return null
        val box = face.boundingBox

        return normalizeFaceBox(
            left = box.left,
            top = box.top,
            width = box.width(),
            height = box.height(),
            imageWidth = inputImage.width,
            imageHeight = inputImage.height,
            isMirrored = isMirrored,
        )
    }

    override fun close() {
        detector.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
