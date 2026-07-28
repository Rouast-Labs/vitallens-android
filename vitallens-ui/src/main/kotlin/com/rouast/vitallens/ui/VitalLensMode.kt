package com.rouast.vitallens.ui

/**
 * The performance/accuracy tradeoff for a scan or monitor session.
 *
 * Swift defines this in `VitalLensMonitorView.swift` (shared from there via `Binding`/`initialMode`
 * params on Scan/Start too); it lives in its own file here instead, since [StartScreen] needs it
 * before `MonitorScreen.kt` exists, and Kotlin has no equivalent reason to couple a shared type's
 * declaration site to one particular consumer.
 */
enum class VitalLensMode {
    STANDARD,
    ECO;

    /** The target camera frame rate for this mode. */
    val fps: Double
        get() = when (this) {
            STANDARD -> 30.0
            ECO -> 15.0
        }
}
