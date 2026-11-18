package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.AudioCaptureSource
import com.example.data.AudioTrackMode
import com.example.data.BitrateOption
import com.example.data.ClotchSettings
import com.example.data.CountdownOption
import com.example.data.FrameRateOption
import com.example.data.OrientationOption
import com.example.data.RecordingMode
import com.example.data.VideoFormat
import com.example.data.VideoResolution
import com.example.diagnostics.DeveloperStatsMonitor
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordUserProfile
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchInnerCardShape
import com.example.ui.components.ClotchInputShape
import com.example.ui.components.DiscordProfileCard
import com.example.ui.components.TactileOptionPill
import com.example.ui.components.TactilePillButton
import com.example.ui.components.TactileSurface
import com.example.ui.components.clotchTonalFieldColors
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ClotchSettings,
    discordState: DiscordConnectionState,
    discordProfile: DiscordUserProfile,
    hasUsageStatsPermission: Boolean,
    isFetchingProfile: Boolean,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectFps: (FrameRateOption) -> Unit,
    onSelectBitrate: (BitrateOption) -> Unit,
    onSelectOrientation: (OrientationOption) -> Unit,
    onSelectVideoFormat: (VideoFormat) -> Unit,
    onSelectRecordingMode: (RecordingMode) -> Unit,
    onSelectAudioCaptureSource: (AudioCaptureSource) -> Unit = {},
    onSelectAudioTrackMode: (AudioTrackMode) -> Unit = {},
    onToggleRecordAudioSeparately: (Boolean) -> Unit,
    onSelectCountdown: (CountdownOption) -> Unit,
    onToggleQuickNotification: (Boolean) -> Unit,
    onToggleDiscordRpc: (Boolean) -> Unit,
    onGameNameChanged: (String) -> Unit,
    onDiscordTokenChanged: (String) -> Unit,
    onTestDiscordConnection: (String) -> Unit,
    onDisconnectDiscord: () -> Unit,
    onOpenSignInDialog: () -> Unit,
    onSignOutDiscord: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onScanAppsNow: () -> Unit,
    onGeminiApiKeyChanged: (String) -> Unit = {},
    onOpenDeveloperDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var tokenInput by remember(settings.discordToken) { mutableStateOf(settings.discordToken) }
    var geminiKeyInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var gameNameInput by remember(settings.gameName) { mutableStateOf(settings.gameName) }
    var showToken by remember { mutableStateOf(false) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var showDevPasswordDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Video capture encoder, format, audio mode & Discord Kizzy RPC",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            DiscordProfileCard(
                profile = discordProfile,
                isLoading = isFetchingProfile,
                onSignInClick = onOpenSignInDialog,
                onSignOutClick = onSignOutDiscord
            )

            // Video Encoder Card
            SettingsSectionCard(
                title = "Video Encoder",
                icon = Icons.Outlined.Tune,
                accentColor = ClotchBlue
            ) {
                OptionGroupLabel("Video Format")
                ExposedDropdownMenuBox(
                    expanded = formatDropdownExpanded,
                    onExpandedChange = { formatDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = when (settings.videoFormat) {
                            VideoFormat.MP4 -> "MP4 (.mp4) — Native MediaRecorder"
                            VideoFormat.MKV -> "MKV (.mkv) — FFmpeg Remux"
                            VideoFormat.THREE_GPP -> "3GPP (.3gp) — Compact Mobile"
                        },
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatDropdownExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            .testTag("video_format_dropdown"),
                        shape = ClotchInputShape,
                        colors = clotchTonalFieldColors(accentColor = ClotchBlue)
                    )
                    ExposedDropdownMenu(
                        expanded = formatDropdownExpanded,
                        onDismissRequest = { formatDropdownExpanded = false },
                        modifier = Modifier.background(ElevatedSlate)
                    ) {
                        VideoFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = when (format) {
                                            VideoFormat.MP4 -> "MP4 (.mp4) — Standard"
                                            VideoFormat.MKV -> "MKV (.mkv) — FFmpeg Matroska"
                                            VideoFormat.THREE_GPP -> "3GPP (.3gp) — Legacy 3GP"
                                        },
                                        color = TextPrimary
                                    )
                                },
                                onClick = {
                                    onSelectVideoFormat(format)
                                    formatDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("format_option_${format.name}")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                OptionGroupLabel("Resolution")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoResolution.entries.forEach { option ->
                        TactileOptionPill(
                            label = option.label,
                            selected = settings.resolution == option,
                            accent = ClotchBlue,
                            onClick = { onSelectResolution(option) },
                            modifier = Modifier.testTag("resolution_${option.name}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OptionGroupLabel("Frame Rate (FPS)")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrameRateOption.entries.forEach { option ->
                        TactileOptionPill(
                            label = option.label,
                            selected = settings.fps == option,
                            accent = ClotchBlue,
                            onClick = { onSelectFps(option) },
                            modifier = Modifier.testTag("fps_${option.fps}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OptionGroupLabel("Video Bitrate")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BitrateOption.entries.forEach { option ->
                        TactileOptionPill(
                            label = option.label,
                            selected = settings.bitrate == option,
                            accent = ClotchBlue,
                            onClick = { onSelectBitrate(option) },
                            modifier = Modifier.testTag("bitrate_${option.mbps}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OptionGroupLabel("Orientation")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OrientationOption.entries.forEach { option ->
                        TactileOptionPill(
                            label = option.label,
                            selected = settings.orientation == option,
                            accent = ClotchBlue,
                            onClick = { onSelectOrientation(option) },
                            modifier = Modifier.testTag("orientation_${option.name}")
                        )
                    }
                }
            }

            // Recording Mode & Capture Controls Card
            SettingsSectionCard(
                title = "Recording Mode & Audio",
                icon = Icons.Outlined.Mic,
                accentColor = SignalEmerald
            ) {
                OptionGroupLabel("Capture Mode")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RecordingMode.entries.forEach { mode ->
                        TactileOptionPill(
                            label = mode.label,
                            selected = settings.recordingMode == mode,
                            accent = SignalEmerald,
                            onClick = { onSelectRecordingMode(mode) },
                            modifier = Modifier.testTag("recording_mode_${mode.name}")
                        )
                    }
                }

                AnimatedVisibility(visible = settings.recordingMode == RecordingMode.AUDIO_AND_VIDEO) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OptionGroupLabel("Recording Audio Mode")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AudioTrackMode.entries.forEach { mode ->
                                val isSelected = settings.audioTrackMode == mode
                                val isSeparate = mode == AudioTrackMode.SEPARATE_AUDIO
                                TactileSurface(
                                    onClick = { onSelectAudioTrackMode(mode) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("audio_track_mode_${mode.name}"),
                                    color = if (isSelected) {
                                        SignalEmerald.copy(alpha = 0.16f)
                                    } else {
                                        CarbonSurface
                                    },
                                    shape = ClotchInnerCardShape,
                                    pressedScale = 0.96f,
                                    hapticType = HapticFeedbackType.TextHandleMove
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isSeparate) Icons.Outlined.Layers else Icons.Outlined.GraphicEq,
                                            contentDescription = mode.label,
                                            tint = if (isSelected) SignalEmerald else TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = mode.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) TextPrimary else TextSecondary
                                            )
                                            Text(
                                                text = mode.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        OptionGroupLabel("Audio Source")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AudioCaptureSource.GAME_ONLY,
                                AudioCaptureSource.MIC_ONLY,
                                AudioCaptureSource.BOTH
                            ).forEach { source ->
                                TactileOptionPill(
                                    label = source.label,
                                    selected = settings.effectiveAudioSource == source,
                                    accent = SignalEmerald,
                                    onClick = { onSelectAudioCaptureSource(source) },
                                    modifier = Modifier.testTag("audio_source_${source.name}")
                                )
                            }
                        }
                        Text(
                            text = when (settings.effectiveAudioSource) {
                                AudioCaptureSource.GAME_ONLY ->
                                    if (settings.audioTrackMode == AudioTrackMode.SEPARATE_AUDIO) {
                                        "Captures internal game/app audio (Android 10+) into the clip and Music/Clotch."
                                    } else {
                                        "Captures internal game/app audio directly inside the video file."
                                    }
                                AudioCaptureSource.MIC_ONLY ->
                                    if (settings.audioTrackMode == AudioTrackMode.SEPARATE_AUDIO) {
                                        "Records microphone voice + saves an isolated mic track for the Editor."
                                    } else {
                                        "Captures microphone voice directly into the recorded clip."
                                    }
                                AudioCaptureSource.BOTH ->
                                    if (settings.audioTrackMode == AudioTrackMode.SEPARATE_AUDIO) {
                                        "Saves Game Audio + Mic as separate .m4a tracks for independent timeline volume & mute."
                                    } else {
                                        "Mixes Game Audio + Microphone cleanly into a single stereo track in your video."
                                    }
                                AudioCaptureSource.NONE -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                AnimatedVisibility(visible = settings.recordingMode == RecordingMode.VIDEO_ONLY) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = CarbonSurface,
                        shape = ClotchInnerCardShape
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            SettingsToggleRow(
                                icon = Icons.Outlined.Mic,
                                title = "Record Audio Separately",
                                subtitle = "Save separate .m4a mic track to Music/Clotch",
                                checked = settings.recordAudioSeparately,
                                onCheckedChange = onToggleRecordAudioSeparately,
                                testTag = "settings_separate_audio_switch"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                OptionGroupLabel("Pre-Record Countdown")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CountdownOption.entries.forEach { option ->
                        TactileOptionPill(
                            label = option.label,
                            selected = settings.countdown == option,
                            accent = SignalEmerald,
                            onClick = { onSelectCountdown(option) },
                            modifier = Modifier.testTag("countdown_${option.seconds}")
                        )
                    }
                }

                SettingsToggleRow(
                    icon = Icons.Outlined.Notifications,
                    title = "Quick Notification Controls",
                    subtitle = "Persistent Start/Pause/Stop controls in notification shade",
                    checked = settings.showQuickNotification,
                    onCheckedChange = onToggleQuickNotification,
                    testTag = "settings_notification_switch"
                )
            }

            // Discord Rich Presence & App Detection Card
            SettingsSectionCard(
                title = "Kizzy Rich Presence & App Detection",
                icon = Icons.Outlined.Hub,
                accentColor = DiscordBlurple
            ) {
                SettingsToggleRow(
                    icon = Icons.Outlined.Hub,
                    title = "Auto-Sync Discord Status",
                    subtitle = "Detects supported games, Lark Player & recording via Kizzy",
                    checked = settings.discordRpcEnabled,
                    onCheckedChange = onToggleDiscordRpc,
                    testTag = "settings_discord_switch"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!hasUsageStatsPermission) {
                        TactilePillButton(
                            onClick = onRequestUsageAccess,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("settings_usage_access_button"),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Text("Grant Usage Access (Apps)")
                        }
                    } else {
                        TactilePillButton(
                            onClick = onScanAppsNow,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("settings_scan_apps_button"),
                            containerColor = CarbonSurface,
                            contentColor = SignalEmerald
                        ) {
                            Text("Usage Access Granted • Scan Apps")
                        }
                    }
                }

                OutlinedTextField(
                    value = gameNameInput,
                    onValueChange = {
                        gameNameInput = it
                        onGameNameChanged(it)
                    },
                    label = { Text("Fallback Game Title") },
                    placeholder = { Text("Mobile Game") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_game_name_input"),
                    shape = ClotchInputShape,
                    colors = clotchTonalFieldColors(accentColor = DiscordBlurple)
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        onDiscordTokenChanged(it)
                    },
                    label = { Text("Discord Account Token") },
                    placeholder = { Text("Paste Discord token for Kizzy RPC") },
                    singleLine = true,
                    visualTransformation = if (showToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showToken = !showToken },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discord_token_input"),
                    shape = ClotchInputShape,
                    colors = clotchTonalFieldColors(accentColor = DiscordBlurple)
                )

                Text(
                    text = "Powered by KizzyRPC (dead8309/KizzyRPC) with UsageStatsManager game + Lark Player detection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TactilePillButton(
                        onClick = { onTestDiscordConnection(tokenInput) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("test_discord_rpc_button"),
                        containerColor = DiscordBlurple,
                        contentColor = TextPrimary
                    ) {
                        Text(
                            text = if (discordState is DiscordConnectionState.Connecting) {
                                "Connecting..."
                            } else {
                                "Connect Kizzy RPC"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (discordState is DiscordConnectionState.Connected) {
                        TactilePillButton(
                            onClick = onDisconnectDiscord,
                            modifier = Modifier.height(48.dp),
                            containerColor = ElevatedSlate,
                            contentColor = TextSecondary
                        ) {
                            Text("Disconnect", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Video Editor AI Auto Captions (Gemini API Key) Card
            SettingsSectionCard(
                title = "Video Editor AI Captions",
                icon = Icons.Outlined.AutoAwesome,
                accentColor = ClotchBlue
            ) {
                Text(
                    text = "Optional: Enter your own Gemini API key for gemini-1.5-flash multi-language Auto Captions. When blank or offline, Clotch automatically uses Android's offline SpeechRecognizer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = {
                        geminiKeyInput = it
                        onGeminiApiKeyChanged(it)
                    },
                    label = { Text("Gemini API Key (AI-Powered Captions)") },
                    placeholder = { Text("AIza... (optional)") },
                    singleLine = true,
                    visualTransformation = if (showGeminiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showGeminiKey = !showGeminiKey },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (showGeminiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showGeminiKey) "Hide Gemini key" else "Show Gemini key",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_api_key_input"),
                    shape = ClotchInputShape,
                    colors = clotchTonalFieldColors(accentColor = ClotchBlue)
                )

                Text(
                    text = if (geminiKeyInput.isBlank()) {
                        "Current caption engine default: Offline SpeechRecognizer"
                    } else {
                        "Gemini key saved • AI-Powered captions unlocked (with offline fallback)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (geminiKeyInput.isBlank()) TextMuted else SignalEmerald
                )
            }

            // Subtle Build Info & Hidden Developer Dashboard Trigger
            TactileSurface(
                onClick = { showDevPasswordDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_dev_unlock_row"),
                color = CarbonSurface,
                shape = ClotchInnerCardShape,
                pressedScale = 0.97f
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Clotch v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "ShutBerry • Tap for Developer Diagnostics",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Unlock Developer Dashboard",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showDevPasswordDialog) {
        DeveloperPasswordPromptDialog(
            onDismiss = { showDevPasswordDialog = false },
            onUnlocked = {
                showDevPasswordDialog = false
                onOpenDeveloperDashboard()
            }
        )
    }
}

@Composable
private fun DeveloperPasswordPromptDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Developer Access",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the developer password to open real-time telemetry and diagnostic dumps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMsg = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMsg != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dev_password_input"),
                    shape = ClotchInputShape,
                    colors = clotchTonalFieldColors(accentColor = ClotchBlue)
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = CrimsonAccent
                    )
                }
            }
        },
        confirmButton = {
            TactilePillButton(
                onClick = {
                    if (DeveloperStatsMonitor.verifyPassword(password)) {
                        onUnlocked()
                    } else {
                        errorMsg = "Incorrect password"
                    }
                },
                modifier = Modifier.testTag("dev_password_submit_button"),
                containerColor = ClotchBlue,
                contentColor = TextPrimary
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun OptionGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary
    )
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = ClotchBlue,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ElevatedSlate,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
