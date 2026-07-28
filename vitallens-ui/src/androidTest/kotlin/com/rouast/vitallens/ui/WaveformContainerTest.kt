package com.rouast.vitallens.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformContainerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsTheRealDisplayNameFromTheCoreEngineAsTitle() {
        val expectedTitle = VitalInfoCache.getInfo("heart_rate")?.displayName ?: "heart_rate"

        composeTestRule.setContent {
            WaveformContainer(vitalId = "heart_rate", history = listOf(1.0, 2.0, 3.0), isReady = true)
        }

        composeTestRule.onNodeWithText(expectedTitle).assertIsDisplayed()
    }

    @Test
    fun fallsBackToTheRawVitalIdWhenMetadataIsUnknown() {
        composeTestRule.setContent {
            WaveformContainer(vitalId = "not_a_real_vital", history = listOf(1.0, 2.0), isReady = true)
        }

        composeTestRule.onNodeWithText("not_a_real_vital").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashingWhenNotReady() {
        composeTestRule.setContent {
            WaveformContainer(
                vitalId = "heart_rate",
                history = listOf(1.0, 2.0, 3.0),
                isReady = false,
                modifier = Modifier.testTag("container").height(100.dp).width(150.dp),
            )
        }

        composeTestRule.onNodeWithTag("container").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashingWhenReadyButHistoryIsEmpty() {
        composeTestRule.setContent {
            WaveformContainer(
                vitalId = "heart_rate",
                history = emptyList(),
                isReady = true,
                modifier = Modifier.testTag("container").height(100.dp).width(150.dp),
            )
        }

        composeTestRule.onNodeWithTag("container").assertIsDisplayed()
    }

    @Test
    fun rendersWithoutCrashingWhenReadyWithData() {
        composeTestRule.setContent {
            WaveformContainer(
                vitalId = "heart_rate",
                history = listOf(1.0, 2.0, 3.0, 2.5, 1.5),
                isReady = true,
                modifier = Modifier.testTag("container").height(100.dp).width(150.dp),
            )
        }

        composeTestRule.onNodeWithTag("container").assertIsDisplayed()
    }
}
