package com.rouast.vitallens.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rouast.vitallens.ui.MonitorScreen
import com.rouast.vitallens.ui.ScanScreen
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DemoApp()
            }
        }
    }
}

/**
 * A minimal tabbed app: Monitor and Scan, each backed directly by the corresponding vitallens-ui
 * screen, with a fallback prompt if no API key or proxy URL is configured.
 *
 * File mode (`FileScreen`/`VitalLens.processVideoFile`) is implemented but not exposed here for
 * now — its underlying decode path isn't yet robust/fast enough across devices to ship. See
 * CLAUDE.md's "File mode status" note.
 */
@Composable
fun DemoApp() {
    val apiKey = BuildConfig.VITALLENS_API_KEY.takeIf { it.isNotBlank() }
    val proxyUrl = BuildConfig.VITALLENS_PROXY_URL.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
    val baseUrl = BuildConfig.VITALLENS_BASE_URL.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()

    if (apiKey == null && proxyUrl == null) {
        MissingKeyScreen()
        return
    }

    val context = LocalContext.current
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
    }
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                    label = { Text("Monitor") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Face, contentDescription = null) },
                    label = { Text("Scan") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> if (cameraPermissionGranted) {
                    MonitorScreen(apiKey = apiKey, proxyUrl = proxyUrl, showWaveforms = true, baseUrl = baseUrl)
                } else {
                    CameraPermissionRequiredScreen()
                }
                1 -> if (cameraPermissionGranted) {
                    ScanScreen(apiKey = apiKey, proxyUrl = proxyUrl, onComplete = {}, baseUrl = baseUrl)
                } else {
                    CameraPermissionRequiredScreen()
                }
            }
        }
    }
}

@Composable
private fun MissingKeyScreen() {
    val uriHandler = LocalUriHandler.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp))
            Text("Authentication Missing", style = MaterialTheme.typography.titleLarge)
            Text(
                "Please set VITALLENS_API_KEY in local.properties (or the environment) to your " +
                    "actual API key, or provide a valid VITALLENS_PROXY_URL.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = { uriHandler.openUri("https://www.rouast.com/api/") }) {
                Text("Get an API Key")
            }
        }
    }
}

@Composable
private fun CameraPermissionRequiredScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Camera permission is required for this tab.", textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}
