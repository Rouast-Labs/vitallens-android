package com.rouast.vitallens.inference

/**
 * A framework-agnostic, normalized (0.0-1.0) rectangle describing a region of interest.
 *
 * Kept independent of `android.graphics.RectF` and the generated `VitalLensCore.Rect` FFI type
 * so both the inference and camera/UI layers can share a single ROI representation without either
 * depending on platform-specific graphics types or generated bindings.
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
