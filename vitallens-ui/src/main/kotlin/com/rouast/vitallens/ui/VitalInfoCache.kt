package com.rouast.vitallens.ui

import androidx.compose.ui.graphics.Color
import com.rouast.vitallens.core.VitalInfo
import com.rouast.vitallens.core.getVitalInfo

private val DEFAULT_BRAND_BLUE = Color(red = 0f, green = 163f / 255f, blue = 252f / 255f)

/** Parses a hex color string (e.g. `"#FF0000"` or `"FF0000"`) into a [Color], or null if unparseable. */
internal fun parseHexColor(hex: String): Color? {
    val sanitized = hex.trim().removePrefix("#")
    val rgb = sanitized.toLongOrNull(16) ?: return null
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
    )
}

/**
 * A thread-safe cache for vital sign display info retrieved from the Core engine, avoiding a
 * repeated native call for metadata that never changes at runtime.
 */
object VitalInfoCache {
    private val cache = mutableMapOf<String, VitalInfo>()
    private val queriedKeys = mutableSetOf<String>()
    private val lock = Any()

    /** Retrieves the display info for a specific vital sign identifier, or null if unknown. */
    fun getInfo(id: String): VitalInfo? = synchronized(lock) {
        if (id in queriedKeys) return@synchronized cache[id]
        val info = getVitalInfo(id)
        if (info != null) cache[id] = info
        queriedKeys.add(id)
        cache[id]
    }

    /** The brand accent color, dynamically sourced from `respiratory_rate`'s color, falling back to a default blue. */
    val brandBlue: Color
        get() = getInfo("respiratory_rate")?.color?.let { parseHexColor(it) } ?: DEFAULT_BRAND_BLUE
}
