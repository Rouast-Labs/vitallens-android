package com.rouast.vitallens.ui

/**
 * The performance/accuracy tradeoff for a scan or monitor session.
 *
 * Lives in its own file rather than inside any one screen: [StartScreen], `ScanScreen.kt`,
 * `MonitorScreen.kt`, and `FileScreen.kt` all reference it, so it belongs to none of them
 * specifically.
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
