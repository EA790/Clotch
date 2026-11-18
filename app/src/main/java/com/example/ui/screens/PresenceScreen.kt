package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ClotchSettings
import com.example.discord.DetectedAppsResult
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordUserProfile
import com.example.recording.RecorderState
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchInputShape
import com.example.ui.components.DiscordLogoBadge
import com.example.ui.components.DiscordProfileCard
import com.example.ui.components.TactileOptionPill
import com.example.ui.components.TactilePillButton
import com.example.ui.components.clotchTonalFieldColors
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

private val PresencePresetGames = listOf(
    "Mobile Game",
    "COD Mobile",
    "Valorant Mobile",
    "PUBG Mobile",
    "Genshin Impact"
)

@Composable
fun PresenceScreen(
    settings: ClotchSettings,
    recorderState: RecorderState,
    discordState: DiscordConnectionState,
    discordPresence: DiscordPresencePayload?,
    discordProfile: DiscordUserProfile,
    detectedApps: DetectedAppsResult,
    hasUsageStatsPermission: Boolean,
    isFetchingProfile: Boolean,
    onGameNameChanged: (String) -> Unit,
    onOpenSignInDialog: () -> Unit,
    onSignOutDiscord: () -> Unit,
    onConnectRpc: () -> Unit,
    onDisconnectRpc: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onScanAppsNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var localGameName by remember(settings.gameName) { mutableStateOf(settings.gameName) }

    val isRecordingOrPaused =
        recorderState is RecorderState.Recording || recorderState is RecorderState.Paused

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Discord Presence",
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 26.sp,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            // Status Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = ClotchCardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DiscordLogoBadge(size = 54.dp)

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val (title, titleColor, subtitle) = when (discordState) {
                            is DiscordConnectionState.Connected -> Triple(
                                "Connected to Discord",
                                SignalEmerald,
                                "Kizzy RPC runs in the background"
                            )
                            DiscordConnectionState.Connecting -> Triple(
                                "Connecting to Discord",
                                WarningAmber,
                                "Connecting via Kizzy RPC..."
                            )
                            is DiscordConnectionState.Error -> Triple(
                                "Discord RPC Error",
                                CrimsonAccent,
                                discordState.message
                            )
                            DiscordConnectionState.Disconnected -> Triple(
                                "Not Connected",
                                TextPrimary,
                                if (settings.discordToken.isBlank()) {
                                    "Sign in below to sync live status"
                                } else {
                                    "Tap Connect to start Kizzy RPC"
                                }
                            )
                        }

                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 18.sp,
                            color = titleColor
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Discord Signed-In Profile Card
            DiscordProfileCard(
                profile = discordProfile,
                isLoading = isFetchingProfile,
                onSignInClick = onOpenSignInDialog,
                onSignOutClick = onSignOutDiscord
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Current Activity Preview",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 17.sp,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            // Current Activity (Preview) Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = ClotchCardShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_clotch_mascot),
                        contentDescription = "Clotch Activity Icon",
                        modifier = Modifier.size(66.dp),
                        contentScale = ContentScale.Fit
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val previewPayload = discordPresence ?: DiscordPresencePayload(
                            isRecording = isRecordingOrPaused,
                            isPaused = recorderState is RecorderState.Paused,
                            gameName = settings.gameName,
                            fps = settings.fps.fps,
                            resolutionLabel = settings.resolution.shortLabel,
                            bitrateMbps = settings.bitrate.mbps,
                            detectedAppName = detectedApps.activeGame?.displayName,
                            detectedAppPackage = detectedApps.activeGame?.packageName,
                            isLarkPlayerRunning = detectedApps.isLarkPlayerRunning
                        )

                        Text(
                            text = previewPayload.formatStatusText(),
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 19.sp,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = previewPayload.formatSubDetails(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // App Detection & Game Name Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = ClotchCardShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "App Detection & Status Title",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )

                    if (!hasUsageStatsPermission) {
                        TactilePillButton(
                            onClick = onRequestUsageAccess,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_usage_access_button"),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Text("Grant Usage Access")
                        }
                    } else {
                        TactilePillButton(
                            onClick = onScanAppsNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("scan_apps_button"),
                            containerColor = ElevatedSlate,
                            contentColor = TextPrimary
                        ) {
                            Text("Scan Recent Apps & Lark")
                        }
                    }

                    OutlinedTextField(
                        value = localGameName,
                        onValueChange = { updated ->
                            localGameName = updated
                            onGameNameChanged(updated)
                        },
                        label = { Text("Fallback Game Name") },
                        placeholder = { Text("Mobile Game") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("game_name_input"),
                        shape = ClotchInputShape,
                        colors = clotchTonalFieldColors(accentColor = ClotchBlue)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresencePresetGames.forEach { preset ->
                            val selected = localGameName.equals(preset, ignoreCase = true)
                            TactileOptionPill(
                                label = preset,
                                selected = selected,
                                accent = ClotchBlue,
                                onClick = {
                                    localGameName = preset
                                    onGameNameChanged(preset)
                                }
                            )
                        }
                    }

                    if (settings.discordToken.isNotBlank()) {
                        if (discordState is DiscordConnectionState.Connected) {
                            TactilePillButton(
                                onClick = onDisconnectRpc,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("discord_connect_button"),
                                containerColor = ElevatedSlate,
                                contentColor = TextPrimary
                            ) {
                                Text("Disconnect Kizzy RPC")
                            }
                        } else {
                            TactilePillButton(
                                onClick = onConnectRpc,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("discord_connect_button"),
                                containerColor = DiscordBlurple,
                                contentColor = TextPrimary
                            ) {
                                Text("Connect Kizzy RPC")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
