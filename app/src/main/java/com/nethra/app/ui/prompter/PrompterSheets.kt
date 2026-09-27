package com.nethra.app.ui.prompter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.nethra.app.ai.ScriptBrief
import com.nethra.app.config.AiConfig
import com.nethra.app.config.CaptionScript
import com.nethra.app.config.PrompterScript
import com.nethra.app.config.ScriptLanguage
import com.nethra.app.teleprompter.PaceFollower
import com.nethra.app.teleprompter.ScrollMode
import com.nethra.app.ui.components.GlassButton
import com.nethra.app.ui.components.IosSection
import com.nethra.app.ui.components.IosSegmented
import com.nethra.app.ui.components.IosSheet
import com.nethra.app.ui.components.IosSlider
import com.nethra.app.ui.components.IosToggleRow
import com.nethra.app.ui.theme.NethraColors

/** Editable brief + script facts. Everything the voice parser guessed can be corrected here. */
@Composable
internal fun BriefSheet(ui: PrompterUi, vm: PrompterViewModel, modifier: Modifier = Modifier) {
    val b = ui.brief
    val set: (ScriptBrief) -> Unit = vm::updateBrief
    IosSheet("Script brief", { vm.openBrief(false) }, modifier) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (b.spokenBrief.isNotBlank()) {
            Text("Heard: “${b.spokenBrief}”", color = NethraColors.TextDim, fontSize = 12.sp)
        }
        Field("Topic", b.topic) { set(b.copy(topic = it)) }
        Field("Audience", b.audience) { set(b.copy(audience = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Platform", b.platform, Modifier.weight(1f)) { set(b.copy(platform = it)) }
            Field("Tone", b.tone, Modifier.weight(1f)) { set(b.copy(tone = it)) }
        }
        Field(
            "Duration (seconds) — default ${AiConfig.DEFAULT_SCRIPT_SECONDS}",
            b.durationSeconds?.toString() ?: "", number = true
        ) { v -> set(b.copy(durationSeconds = v.filter(Char::isDigit).take(4).toIntOrNull()?.coerceIn(10, 1800))) }
        Text(
            "Target: about ${b.targetWords} words for ${b.effectiveSeconds} s",
            color = NethraColors.TextDim, fontSize = 12.sp
        )
        Field("Must include", b.mustInclude) { set(b.copy(mustInclude = it)) }
        Field("Leave out", b.exclude) { set(b.copy(exclude = it)) }
        Field("Call to action", b.callToAction) { set(b.copy(callToAction = it)) }

        ui.scriptError?.let { Text(it, color = NethraColors.Bad, fontSize = 13.sp) }

        ui.script?.let { s ->
            Spacer(Modifier.padding(2.dp))
            Text(s.title.ifBlank { "Current script" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "${s.wordCount} words (target ${s.targetWords}) · about ${s.estimatedSeconds} s · ${s.model}",
                color = NethraColors.TextDim, fontSize = 12.sp
            )
            Text(
                if (s.researched) "Web research was used — ${s.sources.size} source${if (s.sources.size == 1) "" else "s"} cited."
                else "No web research was available for this script; it avoids specific facts it couldn't check.",
                color = NethraColors.TextDim, fontSize = 12.sp
            )
            if (s.isShort || s.possiblyIncomplete) {
                Text(
                    (if (s.possiblyIncomplete) "The model's reply may have been cut off. " else "") +
                        (if (s.isShort) "This script is shorter than the target. " else "") +
                        "Regenerate for a complete version.",
                    color = NethraColors.Warn, fontSize = 13.sp
                )
            }
            if (s.notes.isNotBlank()) Text("Notes: ${s.notes}", color = NethraColors.TextDim, fontSize = 12.sp)
            val uri = LocalUriHandler.current
            s.sources.take(6).forEach { src ->
                Text(
                    "• ${src.title.ifBlank { src.url }}", color = NethraColors.Accent, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { runCatching { uri.openUri(src.url) } }.padding(vertical = 2.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                if (ui.script == null) "Write script" else "Regenerate", vm::generate,
                enabled = !ui.generating && !b.isEmpty, icon = Icons.Filled.AutoAwesome, accent = true
            )
        }
        Text(
            "Scripts are written by the cloud model (${AiConfig.SCRIPT_MODEL}) and need internet. " +
                "There is no offline fallback for scripts.",
            color = NethraColors.TextDim, fontSize = 11.sp
        )
    }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier,
        label = { Text(label, fontSize = 12.sp) }, singleLine = !label.startsWith("Must") && !label.startsWith("Topic"),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = NethraColors.Accent, unfocusedBorderColor = NethraColors.GlassBorder,
            focusedLabelColor = NethraColors.Accent, unfocusedLabelColor = NethraColors.TextDim,
            cursorColor = NethraColors.Accent
        )
    )
}

/**
 * Live view of what the recogniser is doing: engine, state, the words it heard and
 * the last error. Say "Nethra" while this is open to check the wake word.
 */
@Composable
private fun VoiceTest(vm: PrompterViewModel) {
    var text by remember { mutableStateOf(vm.voiceDiagnostics()) }
    LaunchedEffect(Unit) { while (true) { text = vm.voiceDiagnostics(); delay(250) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Voice test — say “Nethra”", color = Color.White, fontSize = 15.sp)
        Text(text, color = NethraColors.CameraYellow, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun SettingsSheet(ui: PrompterUi, vm: PrompterViewModel, modifier: Modifier = Modifier) {
    IosSheet("Teleprompter", vm::toggleSettings, modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            val adaptive = ui.scrollMode == ScrollMode.ADAPTIVE
            IosSection(
                "Scrolling",
                footer = if (adaptive)
                    "Scrolls only while recording. It follows the words you read and glides at your own speaking " +
                        "pace (measured from your voice), and stops when you stop. The WPM below is only the starting " +
                        "pace until NETHRA has measured yours."
                else
                    "Scrolls at a steady speed only while recording, and pauses with the recording. " +
                        "Use ▲ ▼ or drag the script to correct it."
            ) {
                Spacer(Modifier.height(6.dp))
                IosSegmented(
                    listOf("Follow my pace", "Fixed WPM"),
                    if (adaptive) 0 else 1,
                    { vm.setScrollMode(if (it == 0) ScrollMode.ADAPTIVE else ScrollMode.FIXED_WPM) }
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (adaptive) "Starting pace" else "Speed",
                        color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f)
                    )
                    Text("${ui.wpm} WPM", color = NethraColors.TextDim, fontSize = 16.sp)
                }
                IosSlider(
                    ui.wpm.toFloat(), { vm.setWpm((it / 5f).roundToInt() * 5) },
                    PaceFollower.MIN_WPM..PaceFollower.MAX_WPM, steps = 0
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(110 to "Slow", 150 to "Normal", 190 to "Fast").forEach { (w, label) ->
                        Text(
                            "$label · $w", color = if (ui.wpm == w) NethraColors.Accent else NethraColors.TextDim, fontSize = 13.sp,
                            modifier = Modifier.clickable { vm.setWpm(w) }.padding(vertical = 6.dp, horizontal = 2.dp)
                        )
                    }
                }
                if (adaptive) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Your pace now", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("${ui.liveWpm} WPM", color = NethraColors.CameraYellow, fontSize = 16.sp)
                    }
                }
            }
            val autoCaptions by vm.autoCaptions.collectAsStateWithLifecycle()
            val prompterScript by vm.prompterScript.collectAsStateWithLifecycle()
            val captionScript by vm.captionScript.collectAsStateWithLifecycle()
            val lang = ui.language
            IosSection(
                "Language",
                footer = if (!lang.hasScriptChoice) "Scripts, captions and the YouTube text all follow this setting."
                else "Teleprompter: ${lang.mixedName} is ${lang.promptName} in English letters (new scripts are written that way); " +
                    "NETHRA still follows your ${lang.promptName} speech. Captions: ${lang.label} keeps your words in " +
                    "${lang.promptName} script, English translates them, ${lang.mixedName} writes them in English letters."
            ) {
                Spacer(Modifier.height(6.dp))
                IosSegmented(
                    ScriptLanguage.entries.map { it.label },
                    lang.ordinal,
                    { vm.setLanguage(ScriptLanguage.entries[it]) },
                    enabled = !ui.isRecordingActive
                )
                if (lang.hasScriptChoice) {
                    Text("Teleprompter script", color = NethraColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    IosSegmented(
                        PrompterScript.entries.map { it.label(lang) },
                        prompterScript.ordinal,
                        { vm.setPrompterScript(PrompterScript.entries[it]) },
                        enabled = !ui.isRecordingActive
                    )
                    Text("Captions", color = NethraColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    IosSegmented(
                        listOf(CaptionScript.ORIGINAL, CaptionScript.TRANSLATED, CaptionScript.MIXED).map { it.label(lang) },
                        listOf(CaptionScript.ORIGINAL, CaptionScript.TRANSLATED, CaptionScript.MIXED).indexOf(captionScript),
                        { vm.setCaptionScript(listOf(CaptionScript.ORIGINAL, CaptionScript.TRANSLATED, CaptionScript.MIXED)[it]) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            IosSection("Captions", footer = "After each take NETHRA transcribes your speech and saves a second copy with " +
                "word-by-word captions burned in (…_captions.mp4) plus an .srt file. Needs internet.") {
                IosToggleRow("Auto captions", autoCaptions, vm::setAutoCaptions)
            }
            IosSection("Script panel", footer = "Drag the ✥ handle to move the script anywhere and the ◢ corner to resize it. " +
                "Portrait and landscape each remember their own layout, and the text turns with the phone.") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Text size", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("${ui.textSize} pt", color = NethraColors.TextDim, fontSize = 16.sp)
                }
                IosSlider(ui.textSize.toFloat(), { vm.setTextSize(it.roundToInt()) }, 16f..44f)
                Text(
                    "Reset panel position", color = NethraColors.Accent, fontSize = 16.sp,
                    modifier = Modifier.clickable { vm.resetPanel() }.padding(vertical = 8.dp)
                )
            }
            IosSection(
                "Voice",
                footer = "Say “Nethra, start recording” (3-2-1 countdown), “Nethra, pause”, “Nethra, resume” or “Nethra, stop”. " +
                    "Some phones give the microphone only to the camera while recording; if NETHRA stops hearing you, " +
                    "a notice appears and the buttons keep working."
            ) {
                IosToggleRow("Voice commands while recording", ui.voiceWhileRecording, vm::setVoiceWhileRecording, enabled = !ui.isRecordingActive)
                IosToggleRow(
                    "Share NETHRA's mic with the recogniser", ui.useOwnMic, vm::setUseOwnMic, enabled = !ui.isRecordingActive,
                    subtitle = "Used only while recording, so voice keeps working during takes (Android 13+). Outside takes the phone's normal recogniser is used."
                )
                VoiceTest(vm)
                IosToggleRow(
                    "Spoken replies", ui.voiceReplies, vm::setVoiceReplies,
                    subtitle = "“Paused.”, “Resuming.”, the countdown — always said while the camera isn't recording."
                )
                IosToggleRow(
                    "Silence recogniser beeps", ui.silenceChimes, vm::setSilenceChimes,
                    subtitle = "Mutes notification, system and media volume while this screen is open."
                )
            }
        }
    }
}
