package com.rouast.vitallens.inference.model

import kotlinx.serialization.Serializable

/** Represents an aggregated scalar physiological value (e.g. heart rate, respiratory rate). */
@Serializable
data class Vital(
    val value: Double = 0.0,
    val confidence: Double = 0.0,
    val unit: String = "",
    val note: String? = null,
)
