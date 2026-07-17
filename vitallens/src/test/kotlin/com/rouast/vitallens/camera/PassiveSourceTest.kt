package com.rouast.vitallens.camera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import com.rouast.vitallens.inference.ImageOrientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class PassiveSourceTest {

    @Test
    fun `inject delivers a frame with matching metadata through the stream`() = runTest {
        val source = PassiveSource()
        val bitmap = mock<Bitmap>()

        source.inject(bitmap, ImageOrientation.RIGHT, isMirrored = true, timestamp = 1.5)

        val frame = source.stream.first()
        assertSame(bitmap, frame.bitmap)
        assertEquals(ImageOrientation.RIGHT, frame.orientation)
        assertTrue(frame.isMirrored)
        assertEquals(1.5, frame.timestamp, 0.0001)
    }

    @Test
    fun `multiple injects arrive in order`() = runTest {
        val source = PassiveSource()
        val bitmap = mock<Bitmap>()

        source.inject(bitmap, ImageOrientation.UP, isMirrored = false, timestamp = 1.0)
        source.inject(bitmap, ImageOrientation.UP, isMirrored = false, timestamp = 2.0)

        val timestamps = source.stream.take(2).toList().map { it.timestamp }
        assertEquals(listOf(1.0, 2.0), timestamps)
    }

    @Test
    fun `start stop and showPreview are no-ops`() = runTest {
        val source = PassiveSource()
        source.start()
        source.stop()
        source.showPreview(mock<PreviewView>())
    }
}
