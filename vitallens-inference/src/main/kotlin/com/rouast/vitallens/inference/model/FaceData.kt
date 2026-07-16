package com.rouast.vitallens.inference.model

import com.rouast.vitallens.inference.Rect
import kotlinx.serialization.Serializable

/** Detailed information about the face detection process over the processed window. */
@Serializable
data class FaceData(
    /** Bounding boxes per frame as `[minX, minY, maxX, maxY]`, normalized 0.0-1.0. */
    val coordinates: List<List<Double>>? = null,
    val confidence: List<Double>? = null,
    val note: String? = null,
) {
    /** Converts the raw `[minX, minY, maxX, maxY]` coordinate arrays into normalized [Rect]s. */
    val boundingBoxes: List<Rect>
        get() = coordinates.orEmpty().map { c ->
            if (c.size != 4) {
                Rect.ZERO
            } else {
                Rect(
                    x = c[0].toFloat(),
                    y = c[1].toFloat(),
                    width = (c[2] - c[0]).toFloat(),
                    height = (c[3] - c[1]).toFloat(),
                )
            }
        }
}
