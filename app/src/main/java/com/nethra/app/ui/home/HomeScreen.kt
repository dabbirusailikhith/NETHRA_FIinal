package com.nethra.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nethra.app.NethraApplication
import com.nethra.app.ai.local.LocalModelState
import com.nethra.app.config.AiConfig
import com.nethra.app.config.ApiKeyProvider
import com.nethra.app.ui.Screen
import com.nethra.app.media.CleanStatus
import com.nethra.app.ui.components.GlassCard
import com.nethra.app.ui.components.ProgressLine
import com.nethra.app.ui.components.StatusPill
import com.nethra.app.ui.theme.NethraColors

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    val container = (LocalContext.current.applicationContext as NethraApplication).container
    val online by container.network.online.collectAsStateWithLifecycle()
    val gemma by container.gemma.state.collectAsStateWithLifecycle()
    val clean by container.postProcessor.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.gemma.refresh() }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0E1B33), NethraColors.Background, Color(0xFF120B22)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text("NETHRA", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Frame, script, record and publish.", color = NethraColors.TextDim)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (online) "Online" else "Offline", if (online) NethraColors.Good else NethraColors.Warn)
                StatusPill(
                    if (ApiKeyProvider.hasOpenRouterKey) "Cloud key set" else "No cloud key",
                    if (ApiKeyProvider.hasOpenRouterKey) NethraColors.Good else NethraColors.Bad
                )
            }
            if (!ApiKeyProvider.hasOpenRouterKey) {
                Text(
                    "Scripts, transcripts and publish kits need an OpenRouter key. Add OPENROUTER_API_KEY to " +
                        "local.properties and rebuild (SETUP_GUIDE.md §4). Framing works without it.",
                    color = NethraColors.Warn, style = MaterialTheme.typography.bodyMedium
                )
            }

            // The clean export keeps running after the teleprompter is closed.
            when (val s = clean) {
                is CleanStatus.Working -> GlassCard(Modifier.fillMaxWidth(), strong = true) {
                    ProgressLine("Clean copy (spoken commands removed): ${s.stage}", s.progress)
                }
                is CleanStatus.Done -> GlassCard(Modifier.fillMaxWidth(), strong = true, onClick = container.postProcessor::clear) {
                    Text("Clean copy saved: ${s.cleanName} (Movies/NETHRA). The original is kept.", color = NethraColors.Good)
                }
                is CleanStatus.Failed -> GlassCard(Modifier.fillMaxWidth(), strong = true, onClick = container.postProcessor::clear) {
                    Text(s.message, color = NethraColors.Warn)
                }
                CleanStatus.Idle -> Unit
            }

            FeatureCard(
                Icons.Filled.CenterFocusStrong, "Framing coach",
                "Rear camera. Draw a box and NETHRA talks the person into it. Works offline.",
                "On-device detection"
            ) { onOpen(Screen.FRAMING) }
            FeatureCard(
                Icons.Filled.Videocam, "Teleprompter",
                "Front camera. Say \"Nethra, write a script about…\", then \"Nethra, start recording\".",
                "Script: ${AiConfig.SCRIPT_MODEL}"
            ) { onOpen(Screen.PROMPTER) }
            FeatureCard(
                Icons.Filled.Subtitles, "Transcript & publish kit",
                "Pick a video. Get a word-for-word transcript plus YouTube and Instagram text.",
                "Transcript: ${AiConfig.TRANSCRIPTION_MODEL}"
            ) { onOpen(Screen.PUBLISH) }

            GlassCard {
                Text("On-device Gemma (optional)", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(gemmaText(gemma), color = NethraColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun gemmaText(s: LocalModelState): String = when (s) {
    LocalModelState.NotInstalled -> "Not installed. Only used for publish-kit text when the cloud can't be reached. See model/README.md."
    is LocalModelState.Available -> "Installed: ${s.file.name}. Loads on first use."
    LocalModelState.Loading -> "Loading…"
    is LocalModelState.Ready -> "Ready on ${s.backend}: ${s.file.name}"
    is LocalModelState.Failed -> s.message
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String, footer: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = NethraColors.Accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = NethraColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(footer, style = MaterialTheme.typography.labelMedium, color = NethraColors.AccentSoft)
            }
        }
    }
}
