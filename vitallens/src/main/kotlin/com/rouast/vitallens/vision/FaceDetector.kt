package com.rouast.vitallens.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rouast.vitallens.FaceDetecting
import com.rouast.vitallens.FrameImage
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
 * already rotation-corrected [InputImage]) to 0.0-1.0 and applies the mirroring flip. ML Kit
 * already reports top-left-origin coordinates, so no Y-axis flip is needed here — just the
 * normalization and the mirroring adjustment.
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
 * [close] exists because ML Kit's underlying `FaceDetector` client holds native resources and
 * implements `Closeable` — callers must release it explicitly once detection is no longer needed,
 * the same category of cleanup [com.rouast.vitallens.inference.buffer.BufferManager.close] handles
 * for its own native pointer.
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
    override suspend fun detectFace(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean): Rect? =
        detect(InputImage.fromBitmap(bitmap, orientationToRotationDegrees(orientation)), isMirrored)

    /** Reads NV21 frames directly, without converting them to RGB. */
    override suspend fun detectFace(image: FrameImage, orientation: ImageOrientation, isMirrored: Boolean): Rect? {
        val nv21 = (image as? FrameImage.Yuv420)?.nv21 ?: return detectFace(image.toBitmap(), orientation, isMirrored)
        val inputImage = InputImage.fromByteBuffer(
            nv21, image.width, image.height, orientationToRotationDegrees(orientation), InputImage.IMAGE_FORMAT_NV21,
        )
        return detect(inputImage, isMirrored)
    }

    private suspend fun detect(inputImage: InputImage, isMirrored: Boolean): Rect? {
        val face = detector.process(inputImage).await().firstOrNull() ?: return null
        val box = face.boundingBox
        // ML Kit reports the box in upright (rotated) coordinates, but InputImage's width and
        // height are those of the raw, unrotated pixels: swap them for 90 and 270 degrees.
        val sideways = inputImage.rotationDegrees % 180 != 0
        return normalizeFaceBox(
            left = box.left,
            top = box.top,
            width = box.width(),
            height = box.height(),
            imageWidth = if (sideways) inputImage.height else inputImage.width,
            imageHeight = if (sideways) inputImage.width else inputImage.height,
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
