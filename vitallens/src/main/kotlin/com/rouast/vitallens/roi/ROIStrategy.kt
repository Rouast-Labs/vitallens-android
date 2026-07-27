package com.rouast.vitallens.roi

import android.graphics.Bitmap
import com.rouast.vitallens.inference.ImageOrientation
import com.rouast.vitallens.inference.Rect

/**
 * Defines the interface for determining a region of interest (ROI) within a video frame.
 *
 * Implementations are called on every frame, so [determineROI] must return quickly — it should
 * never block on expensive work (e.g. face detection) itself; instead it should kick off such
 * work in the background and return the most recently known ROI immediately.
 */
interface ROIStrategy {
    /**
     * Determines the current region of interest for [bitmap].
     *
     * @return A normalized [Rect] ROI, or `null` if none is currently known.
     */
    suspend fun determineROI(
        bitmap: Bitmap,
        orientation: ImageOrientation,
        isMirrored: Boolean,
        roiMethod: String,
    ): Rect?
}
