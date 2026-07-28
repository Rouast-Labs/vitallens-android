package com.rouast.vitallens.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorStatusBadgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsIdleLabel() {
        composeTestRule.setContent { MonitorStatusBadge(state = MonitorState.IDLE) }
        composeTestRule.onNodeWithText("Idle").assertIsDisplayed()
    }

    @Test
    fun showsSearchingLabel() {
        composeTestRule.setContent { MonitorStatusBadge(state = MonitorState.SEARCHING) }
        composeTestRule.onNodeWithText("Searching...").assertIsDisplayed()
    }

    @Test
    fun showsCalibratingLabelWhileWarmingUp() {
        composeTestRule.setContent { MonitorStatusBadge(state = MonitorState.WARMING_UP) }
        composeTestRule.onNodeWithText("Calibrating...").assertIsDisplayed()
    }

    @Test
    fun showsTrackingLabel() {
        composeTestRule.setContent { MonitorStatusBadge(state = MonitorState.TRACKING) }
        composeTestRule.onNodeWithText("Tracking").assertIsDisplayed()
    }

    @Test
    fun showsCheckPositionLabelOnIssue() {
        composeTestRule.setContent { MonitorStatusBadge(state = MonitorState.ISSUE) }
        composeTestRule.onNodeWithText("Check Position").assertIsDisplayed()
    }
}
