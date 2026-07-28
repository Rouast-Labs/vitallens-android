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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val ROUAST_API_URL = "https://www.rouast.com/api/"

/** Statistical metrics regarding a completed vital signs scan. */
data class ScanStats(val duration: Double, val sampleCount: Int, val avgFaceConf: Double)

/** A UI-ready representation of an estimated vital sign, with pre-formatted display metadata. */
data class ResolvedVital(
    val id: String,
    val title: String,
    val value: Double?,
    val unit: String,
    val format: String,
    val confidence: Double?,
    val emoji: String,
)

/**
 * Displays the final aggregated results of a vital signs scan or file processing operation:
 * primary/secondary vitals, optional PPG/respiratory waveforms, and a details toggle exposing
 * scan stats and per-vital confidence.
 */
@Composable
fun ResultScreen(
    title: String,
    primaryVitals: List<ResolvedVital>,
    secondaryVitals: List<ResolvedVital>,
    stats: ScanStats,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    ppgWaveform: List<Double>? = null,
    respWaveform: List<Double>? = null,
) {
    var showDetails by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

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
                TextButton(onClick = onDone) {
                    Text("Done", style = MaterialTheme.typography.titleMedium, color = VitalInfoCache.brandBlue)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (primaryVitals.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            primaryVitals.forEach { vital ->
                                ScanResultTile(vital, showDetails, Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (secondaryVitals.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            secondaryVitals.forEach { vital ->
                                ScanResultTile(vital, showDetails, Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (!ppgWaveform.isNullOrEmpty()) {
                    item {
                        WaveformContainer(
                            vitalId = "ppg_waveform",
                            history = ppgWaveform,
                            isReady = true,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                }

                if (!respWaveform.isNullOrEmpty()) {
                    item {
                        WaveformContainer(
                            vitalId = "respiratory_waveform",
                            history = respWaveform,
                            isReady = true,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                }

                if (showDetails) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                String.format(
                                    Locale.US, "Total Usage: %.1fs (%df)", stats.duration, stats.sampleCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Text(
                                String.format(Locale.US, "Avg Face Confidence: %.0f%%", stats.avgFaceConf * 100),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { showDetails = !showDetails },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VitalLensColors.Panel, RoundedCornerShape(16.dp)),
                contentPadding = PaddingValues(vertical = 18.dp),
            ) {
                Text(
                    if (showDetails) "Hide Details" else "View Details",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ScanResultTile(vital: ResolvedVital, showDetails: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(VitalLensColors.Panel, RoundedCornerShape(20.dp))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vital.emoji, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(
                vital.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
            )
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (vital.value != null) {
                Text(
                    String.format(Locale.US, vital.format, vital.value),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            } else {
                Text(
                    "--",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.3f),
                )
            }
            Text(vital.unit, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        if (showDetails) {
            Text(
                if (vital.confidence != null) {
                    String.format(Locale.US, "Conf: %.0f%%", vital.confidence * 100)
                } else {
                    "Conf: --"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray.copy(alpha = 0.7f),
            )
        }
    }
}
