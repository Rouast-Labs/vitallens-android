package com.rouast.vitallens.ui

import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs a real composition context (AndroidView needs to actually instantiate a View), so this
 * lives under androidTest, not test — matches the instrumented-test placement already
 * established in vitallens for anything needing a real Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class CameraPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun invokesOnViewAvailableExactlyOnceWithARealPreviewView() {
        val capturedViews = mutableListOf<PreviewView>()

        composeTestRule.setContent {
            CameraPreview(onViewAvailable = { capturedViews.add(it) })
        }
        composeTestRule.waitForIdle()

        assertEquals("Should be called exactly once", 1, capturedViews.size)
        assertNotNull(capturedViews.firstOrNull())
    }

    @Test
    fun doesNotInvokeTheCallbackAgainOnRecomposition() {
        var recompositionTrigger by mutableStateOf(0)
        val capturedViews = mutableListOf<PreviewView>()

        composeTestRule.setContent {
            @Suppress("UNUSED_EXPRESSION")
            recompositionTrigger
            CameraPreview(onViewAvailable = { capturedViews.add(it) })
        }
        composeTestRule.waitForIdle()

        recompositionTrigger = 1
        composeTestRule.waitForIdle()

        assertEquals("Should still only be called once after recomposition", 1, capturedViews.size)
    }
}
