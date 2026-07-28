package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.Rect

private const val FACE_CONF_THRESHOLD = 0.5
private const val PPG_CONF_THRESHOLD = 0.5

private val Rect.midX: Float get() = x + width / 2f
private val Rect.midY: Float get() = y + height / 2f

/**
 * Whether the detected face is adequately centered and large enough within the frame. Shared here
 * rather than duplicated inside `ScanScreen.kt` and `MonitorScreen.kt`, since both screens need
 * the same face-quality check to decide when to show a "hold still"/"adjust position" prompt.
 */
internal fun isFaceGood(box: Rect?): Boolean {
    if (box == null) return false
    val midX = box.midX
    val midY = box.midY
    return midX > 0.3f && midX < 0.7f && midY > 0.3f && midY < 0.7f && box.width > 0.15f
}

/** The average of the last [windowSize] samples in [history], or 0.0 if [history] is empty. */
internal fun rollingAverage(history: List<Double>, windowSize: Int): Double {
    if (history.isEmpty()) return 0.0
    val window = history.takeLast(windowSize)
    return window.sum() / window.size
}

/** Whether recent PPG or face confidence has dropped low enough to warrant a recovery prompt. */
internal fun isLowSignal(
    avgPpgConf: Double,
    avgFaceConf: Double,
    ppgThreshold: Double = PPG_CONF_THRESHOLD,
    faceThreshold: Double = FACE_CONF_THRESHOLD,
): Boolean = avgPpgConf < ppgThreshold || avgFaceConf < faceThreshold
