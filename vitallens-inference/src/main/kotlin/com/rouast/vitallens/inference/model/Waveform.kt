package com.rouast.vitallens.inference.model

import kotlinx.serialization.Serializable

/** Represents a time-series physiological signal (e.g. PPG, respiration waveform). */
@Serializable
data class Waveform(
    val data: List<Float>,
    val confidence: List<Float>,
    val unit: String? = null,
    val note: String? = null,
)
