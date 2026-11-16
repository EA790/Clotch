package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ClotchDarkColorScheme = darkColorScheme(
    primary = ClotchBlue,
    onPrimary = TextPrimary,
    primaryContainer = ClotchBlueMuted,
    onPrimaryContainer = TextPrimary,
    secondary = DiscordBlurple,
    onSecondary = TextPrimary,
    secondaryContainer = DiscordBlurpleMuted,
    onSecondaryContainer = TextPrimary,
    tertiary = SignalEmerald,
    onTertiary = ObsidianBg,
    tertiaryContainer = SignalEmeraldMuted,
    onTertiaryContainer = SignalEmerald,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = CarbonSurface,
    surfaceContainer = SlateCard,
    surfaceContainerHigh = ElevatedSlate,
    surfaceContainerHighest = ElevatedSlate,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
    error = CrimsonAccent,
    onError = TextPrimary
)

private val ClotchShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun ClotchTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClotchDarkColorScheme,
        typography = ClotchTypography,
        shapes = ClotchShapes,
        content = content
    )
}
