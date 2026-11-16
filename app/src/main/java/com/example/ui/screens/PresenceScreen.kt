package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ClotchSettings
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordUserProfile
import com.example.recording.RecorderState
import com.example.ui.components.DiscordLogoBadge
import com.example.ui.components.DiscordProfileCard
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HairlineBorder
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
    isFetchingProfile: Boolean,
    onGameNameChanged: (String) -> Unit,
    onOpenSignInDialog: () -> Unit,
    onSignOutDiscord: () -> Unit,
    onConnectRpc: () -> Unit,
    onDisconnectRpc: () -> Unit,
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Discord Presence",
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            // Reference Image 4 Status Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp)
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
                                "Runs in the background"
                            )
                            DiscordConnectionState.Connecting -> Triple(
                                "Connecting to Discord",
                                WarningAmber,
                                "Handshaking with Gateway v10..."
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
                                    "Tap Connect to start RPC"
                                }
                            )
                        }

                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
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

            // Discord Signed-In Profile Card (username, photo, bio)
            DiscordProfileCard(
                profile = discordProfile,
                isLoading = isFetchingProfile,
                onSignInClick = onOpenSignInDialog,
                onSignOutClick = onSignOutDiscord
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Current Activity (Preview)",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            // Reference Image 4 Current Activity (Preview) Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp)
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
                        modifier = Modifier.size(68.dp),
                        contentScale = ContentScale.Fit
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val activePayload = discordPresence
                        val activityTitle = if (isRecordingOrPaused && activePayload != null) {
                            activePayload.formatStatusText()
                        } else {
                            "Idle"
                        }
                        val activitySubtitle = if (isRecordingOrPaused && activePayload != null) {
                            activePayload.formatSubDetails()
                        } else {
                            "No supported app detected"
                        }

                        Text(
                            text = activityTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activitySubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Game Name & Quick RPC Controls Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Status Game Title",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = localGameName,
                        onValueChange = { updated ->
                            localGameName = updated
                            onGameNameChanged(updated)
                        },
                        label = { Text("Game Name") },
                        placeholder = { Text("Mobile Game") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("game_name_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ClotchBlue,
                            unfocusedBorderColor = HairlineBorder,
                            focusedContainerColor = CarbonSurface,
                            unfocusedContainerColor = CarbonSurface,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresencePresetGames.forEach { preset ->
                            val selected = localGameName.equals(preset, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    localGameName = preset
                                    onGameNameChanged(preset)
                                },
                                label = { Text(preset, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CarbonSurface,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = ClotchBlueMuted,
                                    selectedLabelColor = TextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = HairlineBorder,
                                    selectedBorderColor = ClotchBlue
                                )
                            )
                        }
                    }

                    if (settings.discordToken.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (discordState is DiscordConnectionState.Connected) {
                                OutlinedButton(
                                    onClick = onDisconnectRpc,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("discord_connect_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, HairlineBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = ElevatedSlate,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Text("Disconnect Gateway RPC")
                                }
                            } else {
                                Button(
                                    onClick = onConnectRpc,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("discord_connect_button"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DiscordBlurple,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Text("Connect Gateway RPC")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
