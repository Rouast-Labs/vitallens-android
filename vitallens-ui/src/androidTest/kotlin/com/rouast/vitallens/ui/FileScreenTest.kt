package com.rouast.vitallens.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun processingIndicatorShowsItsMessage() {
        composeTestRule.setContent { FileProcessingIndicator() }
        composeTestRule.onNodeWithText("Processing video...").assertIsDisplayed()
    }

    @Test
    fun errorViewShowsTheMessageAndInvokesOnTryAgain() {
        var tryAgainCalled = false
        composeTestRule.setContent {
            FileErrorView(message = "Something went wrong.", onTryAgain = { tryAgainCalled = true })
        }

        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Something went wrong.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try Again").performClick()
        assertTrue(tryAgainCalled)
    }
}
