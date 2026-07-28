package com.rouast.vitallens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysAllProvidedLabels() {
        composeTestRule.setContent {
            StartScreen(
                title = "Test Title",
                subtitle = "Test Subtitle",
                timingHintLabel = "Test Timing",
                startButtonLabel = "Test Start",
                currentMode = VitalLensMode.ECO,
                onModeChange = {},
                onStart = {},
            )
        }

        composeTestRule.onNodeWithText("Test Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Subtitle").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Timing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Start").assertIsDisplayed()
    }

    @Test
    fun tappingTheStartButtonInvokesOnStart() {
        var startCount = 0

        composeTestRule.setContent {
            StartScreen(
                title = "Title",
                subtitle = "Subtitle",
                timingHintLabel = "Timing",
                startButtonLabel = "Begin",
                currentMode = VitalLensMode.ECO,
                onModeChange = {},
                onStart = { startCount++ },
            )
        }

        composeTestRule.onNodeWithText("Begin").performClick()

        assertEquals(1, startCount)
    }

    @Test
    fun tappingTheModeToggleFlipsToTheOppositeMode() {
        var mode by mutableStateOf(VitalLensMode.ECO)

        composeTestRule.setContent {
            StartScreen(
                title = "Title",
                subtitle = "Subtitle",
                timingHintLabel = "Timing",
                startButtonLabel = "Begin",
                currentMode = mode,
                onModeChange = { mode = it },
                onStart = {},
            )
        }

        composeTestRule.onNodeWithTag("mode_toggle_row").performClick()

        assertEquals(VitalLensMode.STANDARD, mode)
    }

    @Test
    fun hidesTheModeToggleWhenShowModeToggleIsFalse() {
        composeTestRule.setContent {
            StartScreen(
                title = "Title",
                subtitle = "Subtitle",
                timingHintLabel = "Timing",
                startButtonLabel = "Begin",
                currentMode = VitalLensMode.ECO,
                onModeChange = {},
                onStart = {},
                showModeToggle = false,
            )
        }

        composeTestRule.onNodeWithTag("mode_toggle_row").assertDoesNotExist()
    }

    @Test
    fun showsDefaultInstructionTextsWhenNotOverridden() {
        composeTestRule.setContent {
            StartScreen(
                title = "Title",
                subtitle = "Subtitle",
                timingHintLabel = "Timing",
                startButtonLabel = "Begin",
                currentMode = VitalLensMode.ECO,
                onModeChange = {},
                onStart = {},
            )
        }

        composeTestRule.onNodeWithText("Center your face\nin the oval.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hold yourself and\ncamera still.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ensure bright,\nsteady lighting.").assertIsDisplayed()
    }
}
