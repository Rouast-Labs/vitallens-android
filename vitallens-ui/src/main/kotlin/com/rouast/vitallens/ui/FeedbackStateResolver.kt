package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.Rect

private const val FACE_CONF_THRESHOLD = 0.5
private const val PPG_CONF_THRESHOLD = 0.5

private val Rect.midX: Float get() = x + width / 2f
private val Rect.midY: Float get() = y + height / 2f

/**
 * Whether the detected face is adequately centered and large enough within the frame — the check
 * `VitalLensScanView.swift`'s `isFaceGood(_:)` performs inline. Shared here so `ScanScreen.kt`
 * and `MonitorScreen.kt` don't each re-derive it (see iOS's own "TODO: Adopt centralised state
 * management for scan and monitor views from js" comment).
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

/**
 * Whether recent PPG or face confidence has dropped low enough to warrant a recovery prompt.
 * Thresholds match iOS's `VitalLensScanView.swift` (0.5/0.5) rather than vitallens.js's
 * `resolveFeedbackState` (0.8/0.5) — see project memory on iOS/JS divergences defaulted to iOS.
 */
internal fun isLowSignal(
    avgPpgConf: Double,
    avgFaceConf: Double,
    ppgThreshold: Double = PPG_CONF_THRESHOLD,
    faceThreshold: Double = FACE_CONF_THRESHOLD,
): Boolean = avgPpgConf < ppgThreshold || avgFaceConf < faceThreshold
