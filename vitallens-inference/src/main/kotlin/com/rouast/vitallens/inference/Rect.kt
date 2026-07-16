package com.rouast.vitallens.inference

/**
 * A framework-agnostic, normalized (0.0-1.0) rectangle describing a region of interest.
 *
 * Mirrors the role Swift's `CGRect` plays in `VitalLensInference` — a universal value type
 * both the inference and camera/UI layers can share without either depending on
 * platform-specific graphics types (`android.graphics.RectF`) or generated FFI bindings
 * (`VitalLensCore.Rect`).
 */
data class Rect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val maxX: Float get() = x + width
    val maxY: Float get() = y + height

    companion object {
        val ZERO = Rect(0f, 0f, 0f, 0f)
    }
}
