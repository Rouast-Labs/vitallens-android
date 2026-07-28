package com.rouast.vitallens.ui

import androidx.compose.ui.graphics.Color

/** Colors shared across `vitallens-ui`'s screens, matching Swift's literal color values. */
internal object VitalLensColors {
    /** The near-black screen background used throughout Scan/Monitor/File. */
    val Background = Color(red = 0.06f, green = 0.07f, blue = 0.09f)

    /** The dark gray panel background for cards (guide grid, mode toggle, waveform containers). */
    val Panel = Color(red = 0.12f, green = 0.12f, blue = 0.12f)
}
