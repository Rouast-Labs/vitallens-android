package com.rouast.vitallens.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VitalInfoCacheTest {

    @Test
    fun `parseHexColor reads a hex string with a leading hash`() {
        val color = parseHexColor("#FF0000")
        assertEquals(Color(red = 1f, green = 0f, blue = 0f), color)
    }

    @Test
    fun `parseHexColor reads a hex string without a leading hash`() {
        val color = parseHexColor("00A3FC")
        assertEquals(Color(red = 0f, green = 163f / 255f, blue = 252f / 255f), color)
    }

    @Test
    fun `parseHexColor trims surrounding whitespace`() {
        val color = parseHexColor("  #00FF00  ")
        assertEquals(Color(red = 0f, green = 1f, blue = 0f), color)
    }

    @Test
    fun `parseHexColor returns null for an unparseable string`() {
        assertNull(parseHexColor("not-a-color"))
    }

    @Test
    fun `getInfo returns real display info for a known vital`() {
        val info = VitalInfoCache.getInfo("heart_rate")
        assertNotNull("Should resolve info for a known vital id", info)
        assertEquals("heart_rate", info!!.id)
    }

    @Test
    fun `getInfo returns null for an unknown vital and caches that too`() {
        assertNull(VitalInfoCache.getInfo("not_a_real_vital_id"))
        // Calling again should still return null (and not crash) rather than re-throwing —
        // exercises the cached-negative-result path (queriedKeys tracks misses too).
        assertNull(VitalInfoCache.getInfo("not_a_real_vital_id"))
    }

    @Test
    fun `getInfo returns the same value on repeated calls`() {
        val first = VitalInfoCache.getInfo("heart_rate")
        val second = VitalInfoCache.getInfo("heart_rate")
        assertEquals(first, second)
    }

    @Test
    fun `brandBlue resolves to a real color without throwing`() {
        val color = VitalInfoCache.brandBlue
        assertNotNull(color)
    }
}
