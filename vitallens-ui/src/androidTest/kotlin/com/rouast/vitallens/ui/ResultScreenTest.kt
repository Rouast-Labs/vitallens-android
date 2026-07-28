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
class ResultScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stats = ScanStats(duration = 10.0, sampleCount = 300, avgFaceConf = 0.95)

    @Test
    fun displaysTitleAndInvokesOnDoneWhenDoneIsTapped() {
        var doneCalled = false
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = emptyList(),
                secondaryVitals = emptyList(),
                stats = stats,
                onDone = { doneCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Test Complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performClick()
        assertTrue(doneCalled)
    }

    @Test
    fun displaysFormattedVitalValueUnitTitleAndEmoji() {
        val vital = ResolvedVital(
            id = "hr", title = "HR", value = 65.0, unit = "BPM", format = "%.0f", confidence = 0.9, emoji = "❤️",
        )
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = listOf(vital),
                secondaryVitals = emptyList(),
                stats = stats,
                onDone = {},
            )
        }

        composeTestRule.onNodeWithText("HR").assertIsDisplayed()
        composeTestRule.onNodeWithText("65").assertIsDisplayed()
        composeTestRule.onNodeWithText("BPM").assertIsDisplayed()
    }

    @Test
    fun showsPlaceholderWhenVitalValueIsMissing() {
        val vital = ResolvedVital(
            id = "hr", title = "HR", value = null, unit = "BPM", format = "%.0f", confidence = null, emoji = "❤️",
        )
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = listOf(vital),
                secondaryVitals = emptyList(),
                stats = stats,
                onDone = {},
            )
        }

        composeTestRule.onNodeWithText("--").assertIsDisplayed()
    }

    @Test
    fun viewDetailsTogglesStatsAndPerVitalConfidence() {
        val vital = ResolvedVital(
            id = "hr", title = "HR", value = 65.0, unit = "BPM", format = "%.0f", confidence = 0.9, emoji = "❤️",
        )
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = listOf(vital),
                secondaryVitals = emptyList(),
                stats = stats,
                onDone = {},
            )
        }

        composeTestRule.onNodeWithText("View Details").performClick()
        composeTestRule.onNodeWithText("Conf: 90%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total Usage: 10.0s (300f)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Avg Face Confidence: 95%").assertIsDisplayed()

        composeTestRule.onNodeWithText("Hide Details").performClick()
        composeTestRule.onNodeWithText("View Details").assertIsDisplayed()
    }

    @Test
    fun hidesWaveformContainersWhenDataIsNullOrEmpty() {
        val ppgTitle = VitalInfoCache.getInfo("ppg_waveform")?.displayName ?: "ppg_waveform"
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = emptyList(),
                secondaryVitals = emptyList(),
                ppgWaveform = null,
                respWaveform = emptyList(),
                stats = stats,
                onDone = {},
            )
        }

        composeTestRule.onNodeWithText(ppgTitle).assertDoesNotExist()
    }

    @Test
    fun showsWaveformContainersWhenDataIsProvided() {
        val ppgTitle = VitalInfoCache.getInfo("ppg_waveform")?.displayName ?: "ppg_waveform"
        val respTitle = VitalInfoCache.getInfo("respiratory_waveform")?.displayName ?: "respiratory_waveform"
        composeTestRule.setContent {
            ResultScreen(
                title = "Test Complete",
                primaryVitals = emptyList(),
                secondaryVitals = emptyList(),
                ppgWaveform = listOf(0.1, 0.2, 0.3),
                respWaveform = listOf(0.4, 0.5, 0.6),
                stats = stats,
                onDone = {},
            )
        }

        composeTestRule.onNodeWithText(ppgTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(respTitle).assertIsDisplayed()
    }
}
