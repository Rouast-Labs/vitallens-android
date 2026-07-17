package com.rouast.vitallens.inference

import com.rouast.vitallens.core.FaceDetector
import com.rouast.vitallens.core.RoiMethod
import com.rouast.vitallens.core.calculateRoi
import com.rouast.vitallens.core.computeIou

/**
 * A utility for calculating specific Regions of Interest (ROIs) from face bounding boxes
 * and computing intersection metrics.
 */
object ROICalculator {

    /**
     * Calculates a specific region of interest based on a detected face bounding box.
     *
     * @param faceRect The normalized bounding box of the detected face (values from 0.0 to 1.0).
     * @param method A string identifier dictating how the ROI should be extracted (e.g. "face",
     *   "forehead", "upper_body", "upper_body_cropped"). Falls back to "upper_body_cropped" for
     *   any unrecognized value.
     * @return A normalized [Rect] representing the computed region of interest.
     */
    fun calculateROI(faceRect: Rect, method: String): Rect {
        val rustMethod: RoiMethod = when (method) {
            "face" -> RoiMethod.Face
            "forehead" -> RoiMethod.Forehead
            "upper_body" -> RoiMethod.UpperBody
            "upper_body_cropped" -> RoiMethod.UpperBodyCropped
            else -> RoiMethod.UpperBodyCropped
        }

        val result = calculateRoi(
            face = faceRect.toRustRect(),
            method = rustMethod,
            // ML Kit, not Apple Vision, is this SDK's face detector — DEFAULT is the
            // correct choice here, not a literal copy of Swift's hardcoded .appleVision.
            detector = FaceDetector.DEFAULT,
            containerWidth = 1.0f,
            containerHeight = 1.0f,
            forceEven = false,
        )

        return Rect(x = result.x, y = result.y, width = result.width, height = result.height)
    }

    /**
     * Computes the Intersection over Union (IoU) between two rectangles.
     *
     * @return The IoU ratio, from 0.0 (no overlap) to 1.0 (perfect overlap).
     */
    fun computeIoU(a: Rect, b: Rect): Float = computeIou(a.toRustRect(), b.toRustRect())
}
