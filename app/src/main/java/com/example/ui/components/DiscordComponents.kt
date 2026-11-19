package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.R
import com.example.discord.DiscordProfileManager
import com.example.discord.DiscordUserProfile
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.DiscordBlurpleMuted
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberMuted

/**
 * White rounded badge with Discord Clyde vector silhouette matching Reference Image 4.
 */
@Composable
fun DiscordLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    Surface(
        modifier = modifier.size(size),
        color = Color.White,
        shape = RoundedCornerShape(13.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size * 0.68f)) {
                val w = this.size.width
                val h = this.size.height

                // Outer Clyde head silhouette
                val head = Path().apply {
                    moveTo(w * 0.22f, h * 0.18f)
                    cubicTo(w * 0.34f, h * 0.13f, w * 0.43f, h * 0.13f, w * 0.50f, h * 0.16f)
                    cubicTo(w * 0.57f, h * 0.13f, w * 0.66f, h * 0.13f, w * 0.78f, h * 0.18f)
                    cubicTo(w * 0.90f, h * 0.34f, w * 0.95f, h * 0.58f, w * 0.90f, h * 0.77f)
                    cubicTo(w * 0.78f, h * 0.85f, w * 0.67f, h * 0.87f, w * 0.61f, h * 0.85f)
                    lineTo(w * 0.56f, h * 0.76f)
                    cubicTo(w * 0.52f, h * 0.77f, w * 0.48f, h * 0.77f, w * 0.44f, h * 0.76f)
                    lineTo(w * 0.39f, h * 0.85f)
                    cubicTo(w * 0.33f, h * 0.87f, w * 0.22f, h * 0.85f, w * 0.10f, h * 0.77f)
                    cubicTo(w * 0.05f, h * 0.58f, w * 0.10f, h * 0.34f, w * 0.22f, h * 0.18f)
                    close()
                }
                drawPath(path = head, color = DiscordBlurple)

                // Left & right eyes
                drawOval(
                    color = Color.White,
                    topLeft = Offset(w * 0.28f, h * 0.43f),
                    size = Size(w * 0.14f, h * 0.17f)
                )
                drawOval(
                    color = Color.White,
                    topLeft = Offset(w * 0.58f, h * 0.43f),
                    size = Size(w * 0.14f, h * 0.17f)
                )

                // Top-notch inner bridge accent
                drawRoundRect(
                    color = DiscordBlurple,
                    topLeft = Offset(w * 0.41f, h * 0.15f),
                    size = Size(w * 0.18f, h * 0.08f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

@Composable
fun DiscordProfileCard(
    profile: DiscordUserProfile,
    isLoading: Boolean,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        if (profile.isSignedIn) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(ElevatedSlate)
                            .border(2.dp, DiscordBlurple, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = profile.avatarUrl,
                                contentDescription = "Discord avatar for ${profile.displayTitle}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = profile.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Verified Discord account",
                                tint = SignalEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "@${profile.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TactileIconButton(
                        onClick = onSignOutClick,
                        hapticType = HapticFeedbackType.LongPress,
                        modifier = Modifier.testTag("discord_sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign out of Discord",
                            tint = TextSecondary
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "DISCORD BIO",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = profile.bio.ifBlank { "No bio set on this Discord account." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (profile.bio.isNotBlank()) TextPrimary else TextMuted
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(DiscordBlurpleMuted),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = DiscordBlurple,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                text = "Discord Account",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Sign in to show username, avatar & bio",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    TactilePillButton(
                        onClick = onSignInClick,
                        enabled = !isLoading,
                        modifier = Modifier.testTag("discord_sign_in_button"),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        containerColor = DiscordBlurple,
                        contentColor = TextPrimary
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Sign In", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                DiscordSignInDisclaimerBanner(
                    modifier = Modifier.testTag("discord_card_disclaimer")
                )
            }
        }
    }
}

@Composable
fun DiscordSignInDisclaimerBanner(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = WarningAmberMuted.copy(alpha = 0.16f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp)
            )
            Text(
                text = stringResource(id = R.string.discord_signin_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber.copy(alpha = 0.94f)
            )
        }
    }
}

@Composable
fun DiscordSignInDialog(
    initialToken: String,
    initialClientId: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSignInWithToken: (String) -> Unit,
    onLaunchOAuthBrowser: (String, Boolean) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var tokenInput by remember(initialToken) { mutableStateOf(initialToken) }
    var clientIdInput by remember(initialClientId) { mutableStateOf(initialClientId) }
    var showToken by remember { mutableStateOf(false) }
    var useIdentifyFallback by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SlateCard,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DiscordLogoBadge(size = 36.dp)
                    Text(
                        text = "Sign in with Discord",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Surface(
                    color = CarbonSurface,
                    shape = ClotchPillShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Token + RPC", "OAuth2 Browser").forEachIndexed { idx, label ->
                            val selected = selectedTab == idx
                            TactileSurface(
                                onClick = { selectedTab = idx },
                                modifier = Modifier.weight(1f),
                                color = if (selected) ElevatedSlate else Color.Transparent,
                                shape = ClotchPillShape,
                                pressedScale = 0.96f
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) TextPrimary else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedTab == 0) {
                    Text(
                        text = "Signs in via Discord API (/users/@me) to load your username, avatar, and bio, and enables live Rich Presence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Discord Token") },
                        placeholder = { Text("Paste Discord token") },
                        singleLine = true,
                        visualTransformation = if (showToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showToken) "Hide token" else "Show token",
                                    tint = TextSecondary
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_discord_token_input"),
                        shape = ClotchInputShape,
                        colors = clotchTonalFieldColors(accentColor = DiscordBlurple)
                    )
                } else {
                    val clipboardManager = LocalClipboardManager.current
                    var copiedRedirect by remember { mutableStateOf(false) }
                    Text(
                        text = "Implicit OAuth2 (response_type=token). Register this Redirect URI in Discord Developer Portal -> OAuth2 -> Redirects:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Surface(
                        color = CarbonSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = DiscordProfileManager.OAUTH_REDIRECT_URI,
                                style = MaterialTheme.typography.labelMedium,
                                color = ClotchBlue,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(DiscordProfileManager.OAUTH_REDIRECT_URI))
                                    copiedRedirect = true
                                },
                                modifier = Modifier.testTag("copy_oauth_redirect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy redirect URI",
                                    tint = ClotchBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedRedirect) "Copied" else "Copy",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClotchBlue
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        label = { Text("Discord App Client ID") },
                        placeholder = { Text("e.g. 123456789012345678") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_oauth_client_id_input"),
                        shape = ClotchInputShape,
                        colors = clotchTonalFieldColors(accentColor = ClotchBlue)
                    )
                    Surface(
                        color = CarbonSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { useIdentifyFallback = !useIdentifyFallback }
                            .testTag("oauth_identify_fallback_toggle")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Fallback to 'identify' scope only",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (useIdentifyFallback) {
                                        "Scope: identify (safe if Discord rejects rpc)"
                                    } else {
                                        "Default scope: identify rpc"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = useIdentifyFallback,
                                onCheckedChange = { useIdentifyFallback = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = ClotchBlue
                                )
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (selectedTab == 0) {
                        TactilePillButton(
                            onClick = { onSignInWithToken(tokenInput) },
                            enabled = !isLoading && tokenInput.isNotBlank(),
                            modifier = Modifier.testTag("dialog_discord_sign_in_button"),
                            containerColor = DiscordBlurple,
                            contentColor = TextPrimary
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = TextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Sign In & Sync")
                            }
                        }
                    } else {
                        TactilePillButton(
                            onClick = { onLaunchOAuthBrowser(clientIdInput, useIdentifyFallback) },
                            enabled = clientIdInput.isNotBlank(),
                            modifier = Modifier.testTag("dialog_discord_oauth_button"),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Icon(
                                imageVector = Icons.Filled.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Discord")
                        }
                    }
                }

                DiscordSignInDisclaimerBanner(
                    modifier = Modifier.testTag("discord_dialog_disclaimer")
                )
            }
        }
    }
}
