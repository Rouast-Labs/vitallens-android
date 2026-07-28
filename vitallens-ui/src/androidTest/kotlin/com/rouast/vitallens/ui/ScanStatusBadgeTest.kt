package com.rouast.vitallens.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanStatusBadgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsIdleLabel() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.IDLE) }
        composeTestRule.onNodeWithText("Idle").assertIsDisplayed()
    }

    @Test
    fun showsSearchingLabel() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.SEARCHING) }
        composeTestRule.onNodeWithText("Searching").assertIsDisplayed()
    }

    @Test
    fun showsCalibratingLabelWhileWarmingUp() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.WARMING_UP) }
        composeTestRule.onNodeWithText("Calibrating").assertIsDisplayed()
    }

    @Test
    fun showsScanningLabelWhileTracking() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.TRACKING) }
        composeTestRule.onNodeWithText("Scanning").assertIsDisplayed()
    }

    @Test
    fun showsRecoveringLabel() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.RECOVERING) }
        composeTestRule.onNodeWithText("Recovering").assertIsDisplayed()
    }

    @Test
    fun showsIssueLabel() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.ISSUE) }
        composeTestRule.onNodeWithText("Issue").assertIsDisplayed()
    }

    @Test
    fun showsDoneLabelWhenCompleted() {
        composeTestRule.setContent { ScanStatusBadge(state = ScanState.COMPLETED) }
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }
}
