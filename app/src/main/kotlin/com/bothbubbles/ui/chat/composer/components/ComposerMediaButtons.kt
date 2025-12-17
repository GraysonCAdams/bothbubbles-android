package com.bothbubbles.ui.chat.composer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.util.HapticUtils
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Right-side media buttons for the chat composer.
 *
 * Contains:
 * - Camera button: Quick-capture for photos/videos (hides when text is entered)
 * - Image button: Opens gallery picker
 * - Emoji button: Opens emoji keyboard panel
 *
 * The camera button follows Google Messages behavior where it transforms/hides
 * when the user starts typing, as the primary action shifts to sending.
 *
 * @param showCamera Whether to show the camera quick-capture button
 * @param onCameraClick Callback when camera button is tapped
 * @param onImageClick Callback when image/gallery button is tapped
 * @param onEmojiClick Callback when emoji button is tapped
 * @param isEnabled Whether the buttons are enabled (disabled when SMS input is blocked)
 * @param modifier Modifier for the button row
 */
@Composable
fun ComposerMediaButtons(
    showCamera: Boolean,
    onCameraClick: () -> Unit,
    onImageClick: () -> Unit,
    onEmojiClick: () -> Unit,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val haptic = LocalHapticFeedback.current
    val buttonSize = ComposerMotionTokens.Dimension.ActionButtonSize
    val iconSize = 20.dp
    val iconTint = if (isEnabled) inputColors.inputIcon else inputColors.inputIcon.copy(alpha = 0.4f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Camera button - hidden when typing (Google Messages style)
        AnimatedVisibility(
            visible = showCamera,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            IconButton(
                onClick = {
                    HapticUtils.onTap(haptic)
                    onCameraClick()
                },
                modifier = Modifier.size(buttonSize),
                enabled = isEnabled
            ) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = stringResource(R.string.picker_camera),
                    tint = iconTint,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        // Emoji button
        IconButton(
            onClick = {
                HapticUtils.onTap(haptic)
                onEmojiClick()
            },
            modifier = Modifier.size(buttonSize),
            enabled = isEnabled
        ) {
            Icon(
                imageVector = Icons.Outlined.EmojiEmotions,
                contentDescription = stringResource(R.string.emoji),
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }

        // Image/Gallery button
        IconButton(
            onClick = {
                HapticUtils.onTap(haptic)
                onImageClick()
            },
            modifier = Modifier.size(buttonSize),
            enabled = isEnabled
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = stringResource(R.string.image),
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
