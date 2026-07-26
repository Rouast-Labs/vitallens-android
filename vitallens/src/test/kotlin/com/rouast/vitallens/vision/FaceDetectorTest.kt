package com.rouast.vitallens.vision

import com.rouast.vitallens.camera.rotationDegreesToOrientation
import com.rouast.vitallens.inference.ImageOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceDetectorTest {

    @Test
    fun `orientationToRotationDegrees maps each orientation to its clockwise degrees`() {
        assertEquals(0, orientationToRotationDegrees(ImageOrientation.UP))
        assertEquals(0, orientationToRotationDegrees(ImageOrientation.UP_MIRRORED))
        assertEquals(90, orientationToRotationDegrees(ImageOrientation.RIGHT))
        assertEquals(90, orientationToRotationDegrees(ImageOrientation.RIGHT_MIRRORED))
        assertEquals(180, orientationToRotationDegrees(ImageOrientation.DOWN))
        assertEquals(180, orientationToRotationDegrees(ImageOrientation.DOWN_MIRRORED))
        assertEquals(270, orientationToRotationDegrees(ImageOrientation.LEFT))
        assertEquals(270, orientationToRotationDegrees(ImageOrientation.LEFT_MIRRORED))
    }

    @Test
    fun `orientationToRotationDegrees round-trips with rotationDegreesToOrientation`() {
        for (degrees in listOf(0, 90, 180, 270)) {
            val orientation = rotationDegreesToOrientation(degrees)
            assertEquals(degrees, orientationToRotationDegrees(orientation))
        }
    }

    @Test
    fun `normalizeFaceBox divides by image dimensions when unmirrored`() {
        val rect = normalizeFaceBox(
            left = 20,
            top = 10,
            width = 40,
            height = 50,
            imageWidth = 200,
            imageHeight = 100,
            isMirrored = false,
        )
        assertEquals(0.1f, rect.x, 0.0001f)
        assertEquals(0.1f, rect.y, 0.0001f)
        assertEquals(0.2f, rect.width, 0.0001f)
        assertEquals(0.5f, rect.height, 0.0001f)
    }

    @Test
    fun `normalizeFaceBox flips only the x-axis when mirrored`() {
        val unmirrored = normalizeFaceBox(
            left = 20,
            top = 10,
            width = 40,
            height = 50,
            imageWidth = 200,
            imageHeight = 100,
            isMirrored = false,
        )
        val mirrored = normalizeFaceBox(
            left = 20,
            top = 10,
            width = 40,
            height = 50,
            imageWidth = 200,
            imageHeight = 100,
            isMirrored = true,
        )

        val expectedMirroredX = 1.0f - unmirrored.x - unmirrored.width
        assertEquals(expectedMirroredX, mirrored.x, 0.0001f)
        assertEquals(unmirrored.y, mirrored.y, 0.0001f)
        assertEquals(unmirrored.width, mirrored.width, 0.0001f)
        assertEquals(unmirrored.height, mirrored.height, 0.0001f)
    }
}
