package com.rouast.vitallens

import android.graphics.Bitmap
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect

/** Defines the interface for face detection algorithms. */
interface FaceDetecting {
    /**
     * Detects a face within the provided frame.
     *
     * @return The bounding box of the detected face in normalized coordinates (0.0-1.0), or
     *   `null` if no face is found.
     */
    suspend fun detectFace(bitmap: Bitmap, orientation: ImageOrientation, isMirrored: Boolean): Rect?

    /**
     * Detects a face within [image]. The default converts it to a [Bitmap]; implementations that
     * can read YUV directly should override this.
     */
    suspend fun detectFace(image: FrameImage, orientation: ImageOrientation, isMirrored: Boolean): Rect? =
        detectFace(image.toBitmap(), orientation, isMirrored)
}
