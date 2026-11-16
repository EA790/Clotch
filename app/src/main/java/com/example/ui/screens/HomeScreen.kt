package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ClotchSettings
import com.example.discord.DiscordConnectionState
import com.example.recording.RecorderState
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun HomeScreen(
    settings: ClotchSettings,
    recorderState: RecorderState,
    discordState: DiscordConnectionState,
    clipsCount: Int,
    onOpenPresence: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenClips: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isRecording = recorderState is RecorderState.Recording
    val isPaused = recorderState is RecorderState.Paused

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Hero Mascot & Brand Block matching Desktop/Reference layout
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 36.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_clotch_mascot),
                contentDescription = "Clotch Mascot",
                modifier = Modifier.size(164.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Clotch",
                style = MaterialTheme.typography.displaySmall,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Did You Clip that \"CLOTCH\"?",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        // Bottom 2-Column + Full-Width Action Deck matching Desktop/Reference layout
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Left Card: Presence
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(128.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenPresence)
                        .testTag("home_presence_card"),
                    color = SlateCard,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        if (discordState is DiscordConnectionState.Connected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(SignalEmerald)
                            )
                        }
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "Discord Presence",
                                tint = ClotchBlue,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "Presence",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Right Card: Recording
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(128.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenRecording)
                        .testTag("home_recording_card"),
                    color = SlateCard,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        if (isRecording || isPaused) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) CrimsonAccent else WarningAmber)
                            )
                        }
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) {
                                    Icons.Filled.FiberManualRecord
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = "Screen Recording",
                                tint = when {
                                    isRecording -> CrimsonAccent
                                    isPaused -> WarningAmber
                                    else -> Color(0xFFB4BED1)
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Recording",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = when {
                                    isRecording -> "Live • ${settings.fps.fps}FPS"
                                    isPaused -> "Paused"
                                    else -> "${settings.resolution.shortLabel} • ${settings.fps.fps}FPS"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isRecording) CrimsonAccent else TextSecondary
                            )
                        }
                    }
                }
            }

            // Full-width bottom action card (Saved Clips)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenClips)
                    .testTag("home_clips_card"),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (clipsCount > 0) "Saved Clips ($clipsCount)" else "Saved Clips",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
