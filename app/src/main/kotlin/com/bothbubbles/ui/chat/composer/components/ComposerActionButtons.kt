package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Left-side action button for the chat composer.
 *
 * The add button rotates 45 degrees to form an "X" when the media picker is expanded,
 * providing clear visual feedback that tapping it again will close the panel.
 *
 * This follows the Google Messages pattern where the "+" button transforms
 * to indicate dismissal action when panels are open.
 *
 * @param isExpanded Whether the media picker panel is currently expanded
 * @param onClick Callback when the button is tapped
 * @param isEnabled Whether the button is enabled (disabled when SMS input is blocked)
 * @param modifier Modifier for the button
 */
@Composable
fun ComposerActionButtons(
    isExpanded: Boolean,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val haptic = LocalHapticFeedback.current

    // Rotation animation: 0° (plus) → 45° (X shape)
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = tween(
            durationMillis = ComposerMotionTokens.Duration.NORMAL,
            easing = FastOutSlowInEasing
        ),
        label = "addButtonRotation"
    )

    // Color animation: subtle icon → primary when expanded
    val backgroundColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            inputColors.inputIcon.copy(alpha = 0.7f)
        },
        animationSpec = tween(
            durationMillis = ComposerMotionTokens.Duration.NORMAL
        ),
        label = "addButtonColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            inputColors.inputFieldBackground
        },
        animationSpec = tween(
            durationMillis = ComposerMotionTokens.Duration.NORMAL
        ),
        label = "addButtonIconColor"
    )

    Surface(
        onClick = {
            HapticUtils.onTap(haptic)
            onClick()
        },
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = if (isEnabled) backgroundColor else backgroundColor.copy(alpha = 0.4f),
        enabled = isEnabled
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (isExpanded) {
                    "Close"
                } else {
                    stringResource(R.string.attach_file)
                },
                tint = iconColor,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}
