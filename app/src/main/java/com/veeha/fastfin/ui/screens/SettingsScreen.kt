package com.veeha.fastfin.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.veeha.fastfin.BuildConfig
import com.veeha.fastfin.data.AccentName
import com.veeha.fastfin.data.HdrMode
import com.veeha.fastfin.data.PlayerLayout
import com.veeha.fastfin.data.StreamingQuality
import com.veeha.fastfin.playback.DeviceCapabilities
import com.veeha.fastfin.playback.HdrType
import com.veeha.fastfin.ui.LocalGraph
import com.veeha.fastfin.ui.LocalLayout
import com.veeha.fastfin.ui.LocalTabBarTop
import com.veeha.fastfin.ui.LocalSession
import com.veeha.fastfin.ui.components.Panel
import com.veeha.fastfin.ui.components.LargeTitle
import com.veeha.fastfin.ui.components.Lucide
import com.veeha.fastfin.ui.components.pressable
import com.veeha.fastfin.ui.theme.FF
import com.veeha.fastfin.ui.theme.LocalAccent
import com.veeha.fastfin.ui.theme.color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(bottomInset: Dp) {
    val graph = LocalGraph.current
    val session = LocalSession.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val caps by produceState<DeviceCapabilities?>(null) { value = graph.capabilities.await() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    val context = view.context

    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { SingletonImageLoader.get(context).diskCache?.size ?: 0L }
    }

    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(horizontal = LocalLayout.current.centered(720.dp))
            .padding(top = 8.dp + LocalTabBarTop.current, bottom = bottomInset + 40.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        LargeTitle("Settings")

        Section(
            "Server",
            graph.sessions.storageWarning?.let { "Keystore unavailable, so credentials are kept in app storage instead. ($it)" },
        ) {
            InfoRow(Lucide.Server, "Address", session.host)
            Divider()
            InfoRow(Lucide.User, "User", session.userName.ifEmpty { session.userId.take(8) + "…" })
            Divider()
            InfoRow(Lucide.Phone, "Device ID", session.deviceId.take(8) + "…")
        }

        Section("Accent", "Colours the tab bar, progress bars and highlights.") {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                AccentName.entries.forEach { accent ->
                    val selected = settings.accent == accent
                    Column(
                        Modifier.pressable(role = Role.RadioButton) {
                            tick()
                            graph.settings.update { it.copy(accent = accent) }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(44.dp).border(2.dp, if (selected) FF.Text else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(accent.color))
                        }
                        Text(accent.label, Modifier.padding(top = 8.dp), color = if (selected) FF.Text else FF.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Section("Streaming quality", "Above this bitrate the server converts the video down. Original plays files untouched.") {
            Segmented(StreamingQuality.entries, settings.quality, { it.label }) { q ->
                tick()
                graph.settings.update { it.copy(quality = q) }
            }
        }

        Section("HDR", hdrFooter(settings.hdr, caps)) {
            Segmented(HdrMode.entries, settings.hdr, { it.label }) { mode ->
                tick()
                graph.settings.update { it.copy(hdr = mode) }
            }
        }

        Section(
            "Player controls",
            if (settings.playerLayout == PlayerLayout.Bottom) {
                "Play and skip sit with the timeline along the bottom, leaving the picture completely clear."
            } else {
                "Large play and skip buttons in the middle of the screen, easy to hit without looking."
            },
        ) {
            Segmented(PlayerLayout.entries, settings.playerLayout, { it.label }) { layout ->
                tick()
                graph.settings.update { it.copy(playerLayout = layout) }
            }
        }

        Section("Picture in Picture", "Leaving the app while a video plays shrinks it into a floating window.") {
            Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.PictureInPicture, null, Modifier.size(17.dp), tint = FF.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text("Automatic", Modifier.weight(1f), color = FF.Text, fontSize = 15.sp)
                Switch(
                    checked = settings.autoPip,
                    onCheckedChange = { on -> graph.settings.update { it.copy(autoPip = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current.color, checkedThumbColor = FF.OnLight),
                )
            }
        }

        Section("This device", "Read from the hardware at launch. Anything listed plays straight from the file, MKV included, with no work for the server.") {
            val c = caps
            if (c == null) {
                InfoRow(Lucide.Cpu, "Decoders", "Checking…")
            } else {
                InfoRow(Lucide.Cpu, "Video", c.video.joinToString(" · ") { it.label })
                Divider()
                InfoRow(Lucide.Sparkles, "HDR decoding", c.decodableHdr.sortedBy { it.ordinal }.joinToString(" · ") { it.label }.ifEmpty { "None" })
                Divider()
                InfoRow(Lucide.Monitor, "Display", c.displayHdr.sortedBy { it.ordinal }.joinToString(" · ") { it.label }.ifEmpty { "SDR" })
                Divider()
                InfoRow(Lucide.Speaker, "Audio", c.audioDecoders.filterNot { it.startsWith("pcm") }.sorted().joinToString(" · ") { it.uppercase() })
                if (c.passthrough.isNotEmpty()) {
                    Divider()
                    InfoRow(Lucide.Waves, "Passthrough", c.passthrough.sorted().joinToString(" · ") { it.uppercase() })
                }
                Divider()
                InfoRow(Lucide.Gauge, "Max resolution", c.maxResolution)
            }
        }

        Section("Storage") {
            InfoRow(Lucide.HardDrive, "Artwork cache", if (cacheBytes < 0) "…" else formatBytes(cacheBytes))
            Divider()
            Box(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).pressable(pressedScale = 0.99f) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val loader = SingletonImageLoader.get(context)
                            loader.memoryCache?.clear()
                            loader.diskCache?.clear()
                        }
                        cacheBytes = 0
                    }
                }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Clear artwork cache", color = LocalAccent.current.color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Section("About") {
            InfoRow(Lucide.Info, "Version", BuildConfig.VERSION_NAME)
        }

        Panel(
            Modifier.fillMaxWidth().height(52.dp).pressable(pressedScale = 0.98f) { confirmSignOut = true },
            shape = FF.ShapeLg, contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.LogOut, null, Modifier.size(17.dp), tint = FF.Danger)
                Spacer(Modifier.width(8.dp))
                Text("Sign Out", color = FF.Danger, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of FastFin?") },
            text = { Text("You'll need your ${session.host} credentials to sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch { graph.signOut() }
                }) { Text("Sign Out", color = FF.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel", color = FF.Text) } },
            containerColor = FF.Hover,
            titleContentColor = FF.Text,
            textContentColor = FF.TextSecondary,
        )
    }
}

private fun hdrFooter(mode: HdrMode, caps: DeviceCapabilities?): String {
    val display = caps?.displayHdr.orEmpty()
    val screen = if (display.isEmpty()) "This screen is SDR, so HDR is tone-mapped by the server." else
        "This screen shows ${display.sortedBy { it.ordinal }.joinToString(", ") { it.label }}."
    return when (mode) {
        HdrMode.Auto -> "HDR10, HDR10+, HLG and Dolby Vision play in HDR when this device can show them. $screen"
        HdrMode.Always -> "Sends HDR whenever the decoder can, even if the display doesn't report it. For TV boxes."
        HdrMode.Sdr -> "The server converts HDR to SDR. Use this if HDR looks wrong on this screen."
    } + if (caps != null && HdrType.DolbyVision !in caps.decodableHdr) " Dolby Vision 8.1 plays as HDR10." else ""
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun Section(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), Modifier.padding(horizontal = 12.dp), color = FF.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Panel(Modifier.fillMaxWidth(), shape = FF.ShapeLg) { Column { content() } }
        if (footer != null) Text(footer, Modifier.padding(horizontal = 12.dp), color = FF.TextDim, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(17.dp), tint = FF.TextSecondary) }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = FF.Text, fontSize = 15.sp)
        Text(value, Modifier.widthIn(max = 220.dp), color = FF.TextDim, fontSize = 14.sp, textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(0.5.dp).background(FF.Rim))
}

/** Pill segments, as on iOS: the selected one is a white capsule. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .pressable(role = Role.RadioButton) { onSelect(option) }
                    .clip(FF.Pill)
                    .background(if (on) FF.Text else FF.Field),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (on) FF.OnLight else FF.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
