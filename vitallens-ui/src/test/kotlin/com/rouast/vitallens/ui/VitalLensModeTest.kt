package com.rouast.vitallens.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VitalLensModeTest {

    @Test
    fun `standard mode targets 30fps`() {
        assertEquals(30.0, VitalLensMode.STANDARD.fps, 0.0)
    }

    @Test
    fun `eco mode targets 15fps`() {
        assertEquals(15.0, VitalLensMode.ECO.fps, 0.0)
    }
}
