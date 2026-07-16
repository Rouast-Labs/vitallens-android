package com.rouast.vitallens.inference.model

import com.rouast.vitallens.inference.Rect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes coordinates confidence and note`() {
        val face = json.decodeFromString(
            FaceData.serializer(),
            """{"coordinates": [[0.1, 0.1, 0.2, 0.2]], "confidence": [0.99], "note": "Face found"}""",
        )
        assertEquals(1, face.coordinates?.size)
        assertEquals("Face found", face.note)
    }

    @Test
    fun `decodes with all fields absent`() {
        val face = json.decodeFromString(FaceData.serializer(), "{}")
        assertEquals(null, face.coordinates)
        assertEquals(null, face.confidence)
        assertEquals(null, face.note)
    }

    @Test
    fun `boundingBoxes converts minX-minY-maxX-maxY coordinates to Rect`() {
        val face = FaceData(coordinates = listOf(listOf(0.1, 0.2, 0.4, 0.6)), confidence = listOf(0.9), note = null)
        val boxes = face.boundingBoxes
        assertEquals(1, boxes.size)
        assertEquals(Rect(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f), boxes.first())
    }

    @Test
    fun `boundingBoxes is empty when coordinates is null`() {
        val face = FaceData(coordinates = null, confidence = null, note = null)
        assertEquals(emptyList<Rect>(), face.boundingBoxes)
    }
}
