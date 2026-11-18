package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.ShimmerHighlight
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Reusable press-scale modifier using spring physics for tactile, human-crafted responsiveness.
 */
fun Modifier.tactilePressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.95f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tactile_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Pill-shaped primary button with subtle scale-down physics and haptic confirmation.
 */
@Composable
fun TactilePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    hapticType: HapticFeedbackType = HapticFeedbackType.LongPress,
    containerColor: Color = ClotchBlue,
    contentColor: Color = TextPrimary,
    disabledContainerColor: Color = ElevatedSlate,
    disabledContentColor: Color = TextMuted,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Button(
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(50),
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .tactilePressScale(interactionSource, pressedScale),
        content = content
    )
}

/**
 * Soft tonal Surface pill/card that responds with spring compression and haptics,
 * separating from neighbors via tonal depth rather than 1px borders.
 */
@Composable
fun TactileSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = SlateCard,
    contentColor: Color = TextPrimary,
    tonalElevation: Dp = 0.dp,
    pressedScale: Float = 0.96f,
    hapticType: HapticFeedbackType = HapticFeedbackType.TextHandleMove,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    Surface(
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        modifier = modifier.tactilePressScale(interactionSource, pressedScale),
        content = content
    )
}

/**
 * Pill-shaped tonal selection chip without harsh borders, animating smoothly between
 * unselected CarbonSurface/ElevatedSlate and vibrant tinted active fill.
 */
@Composable
fun TactileOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = ClotchBlue,
    unselectedColor: Color = CarbonSurface,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.22f) else unselectedColor,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "pill_bg"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) TextPrimary else TextSecondary,
        animationSpec = tween(durationMillis = 160),
        label = "pill_fg"
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .tactilePressScale(interactionSource, pressedScale = 0.94f)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(color = accent)
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = textStyle,
            color = labelColor
        )
    }
}

/**
 * Tactile IconButton with press compression and gentle haptic response.
 */
@Composable
fun TactileIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType = HapticFeedbackType.TextHandleMove,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current

    IconButton(
        onClick = {
            haptic.performHapticFeedback(hapticType)
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .tactilePressScale(interactionSource, pressedScale = 0.92f),
        content = content
    )
}

/**
 * Borderless tonal colors for OutlinedTextField so inputs use 16.dp soft fills
 * instead of boxy 1px borders.
 */
@Composable
fun clotchTonalFieldColors(
    accentColor: Color = ClotchBlue,
    containerColor: Color = CarbonSurface
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accentColor.copy(alpha = 0.65f),
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    errorBorderColor = CrimsonAccent,
    focusedContainerColor = containerColor,
    unfocusedContainerColor = containerColor,
    disabledContainerColor = containerColor,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = accentColor,
    unfocusedLabelColor = TextSecondary,
    cursorColor = accentColor
)

val ClotchInputShape = RoundedCornerShape(16.dp)
val ClotchCardShape = RoundedCornerShape(20.dp)
val ClotchInnerCardShape = RoundedCornerShape(16.dp)
val ClotchPillShape = RoundedCornerShape(50)

/**
 * Smooth animated shimmer skeleton box for loading lists and thumbnail placeholders.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    baseColor: Color = SlateCard,
    highlightColor: Color = ShimmerHighlight
) {
    val transition = rememberInfiniteTransition(label = "clotch_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    val startX = -400f + progress * 1400f
    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(startX, 0f),
        end = Offset(startX + 420f, 220f)
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}
