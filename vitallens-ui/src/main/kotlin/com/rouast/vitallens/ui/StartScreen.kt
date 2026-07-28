package com.rouast.vitallens.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val ROUAST_API_URL = "https://www.rouast.com/api/"

/** One icon+text instructional guide shown on [StartScreen] ([GuideItem]'s content). */
data class GuideInstruction(val icon: ImageVector, val text: String)

private val DEFAULT_INSTRUCTION_1 = GuideInstruction(Icons.Filled.AccountCircle, "Center your face\nin the oval.")
private val DEFAULT_INSTRUCTION_2 = GuideInstruction(Icons.Filled.PauseCircleFilled, "Hold yourself and\ncamera still.")

/**
 * A reusable screen presented before a scanning or monitoring session begins. Displays
 * instructional guides, timing hints, and an optional Eco/Standard mode toggle.
 *
 * [currentMode]/[onModeChange] together hoist the mode selection to the caller, so a single mode
 * value can stay in sync between this screen and whatever session it configures.
 */
@Composable
fun StartScreen(
    title: String,
    subtitle: String,
    timingHintLabel: String,
    startButtonLabel: String,
    currentMode: VitalLensMode,
    onModeChange: (VitalLensMode) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    instruction1: GuideInstruction = DEFAULT_INSTRUCTION_1,
    instruction2: GuideInstruction = DEFAULT_INSTRUCTION_2,
    showModeToggle: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VitalLensColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val uriHandler = LocalUriHandler.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.vitallens_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri(ROUAST_API_URL) },
                )
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.weight(1f))

            Text(
                subtitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VitalLensColors.Panel, RoundedCornerShape(20.dp))
                    .padding(vertical = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    GuideItem(instruction1.icon, instruction1.text, Modifier.weight(1f))
                    GuideItem(instruction2.icon, instruction2.text, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    GuideItem(Icons.Filled.WbSunny, "Ensure bright,\nsteady lighting.", Modifier.weight(1f))
                    GuideItem(Icons.Filled.AccessTime, timingHintLabel, Modifier.weight(1f))
                }
            }

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VitalInfoCache.brandBlue),
                contentPadding = PaddingValues(vertical = 18.dp),
            ) {
                Text(startButtonLabel, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            if (showModeToggle) {
                ModeToggleRow(currentMode, onModeChange)
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeToggleRow(currentMode: VitalLensMode, onModeChange: (VitalLensMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mode_toggle_row")
            .clickable {
                onModeChange(if (currentMode == VitalLensMode.ECO) VitalLensMode.STANDARD else VitalLensMode.ECO)
            }
            .background(VitalLensColors.Panel, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
        ) {
            ModeToggleIcon(Icons.Filled.Spa, isActive = currentMode == VitalLensMode.ECO)
            ModeToggleIcon(Icons.Filled.Bolt, isActive = currentMode == VitalLensMode.STANDARD)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (currentMode == VitalLensMode.ECO) "Eco Mode" else "Standard Mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                if (currentMode == VitalLensMode.ECO) {
                    "Standard accuracy, for slower connections and devices"
                } else {
                    "High accuracy, for fast connections and devices"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                maxLines = 2,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ModeToggleIcon(icon: ImageVector, isActive: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(if (isActive) VitalInfoCache.brandBlue else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (isActive) Color.White else Color.Gray)
    }
}

/** An icon + instructional caption used in [StartScreen]'s guide grid. */
@Composable
fun GuideItem(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = VitalInfoCache.brandBlue)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = Color.LightGray,
        )
    }
}
