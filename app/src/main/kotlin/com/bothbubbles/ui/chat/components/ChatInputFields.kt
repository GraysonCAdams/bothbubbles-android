package com.bothbubbles.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.theme.BubbleColors

/**
 * Text input field row for normal message composition.
 *
 * Contains:
 * - Add attachment button (rotates to X when picker is expanded)
 * - Text field for message input
 * - Emoji button
 * - Image/gallery button
 *
 * The field background has a subtle color tint based on the current send mode
 * (blue for iMessage, green for SMS) to provide visual context.
 *
 * @param text Current text value
 * @param onTextChange Callback when text changes
 * @param onAttachClick Callback when add button is clicked
 * @param onEmojiClick Callback when emoji button is clicked
 * @param onImageClick Callback when image button is clicked
 * @param currentSendMode Current send mode (iMessage or SMS)
 * @param isPickerExpanded Whether the attachment picker is expanded
 * @param smsInputBlocked Whether SMS input is blocked (not default SMS app)
 * @param onSmsInputBlockedClick Callback when blocked input is tapped
 * @param inputColors Theme colors for styling
 */
@Composable
fun ChatInputFieldRow(
    text: String,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onImageClick: () -> Unit,
    currentSendMode: ChatSendMode,
    isPickerExpanded: Boolean,
    smsInputBlocked: Boolean,
    onSmsInputBlockedClick: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val isSmsMode = currentSendMode == ChatSendMode.SMS

    // Subtle color tint provides context before user reaches send button
    val modeTint by animateColorAsState(
        targetValue = if (isSmsMode) {
            inputColors.inputFieldTintSms
        } else {
            inputColors.inputFieldTintIMessage
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "inputFieldTint"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        shape = RoundedCornerShape(18.dp),
        // Blend base color with mode tint for subtle context
        color = inputColors.inputFieldBackground.copy(
            red = (inputColors.inputFieldBackground.red + modeTint.red * modeTint.alpha) / (1f + modeTint.alpha),
            green = (inputColors.inputFieldBackground.green + modeTint.green * modeTint.alpha) / (1f + modeTint.alpha),
            blue = (inputColors.inputFieldBackground.blue + modeTint.blue * modeTint.alpha) / (1f + modeTint.alpha)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add button - solid circle that rotates to X when drawer open
            AddAttachmentButton(
                onClick = onAttachClick,
                isExpanded = isPickerExpanded,
                inputColors = inputColors
            )

            ComposerTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = getPlaceholderForSendMode(
                    sendMode = currentSendMode,
                    isBlocked = smsInputBlocked
                ),
                enabled = !smsInputBlocked,
                readOnly = smsInputBlocked,
                inputColors = inputColors,
                onDisabledClick = if (smsInputBlocked) onSmsInputBlockedClick else null
            )

            // Emoji icon button
            IconButton(
                onClick = onEmojiClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = stringResource(R.string.emoji),
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Image/Gallery icon button
            IconButton(
                onClick = onImageClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = stringResource(R.string.image),
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Circular add button that rotates 45 degrees when expanded (becoming an X).
 */
@Composable
private fun AddAttachmentButton(
    onClick: () -> Unit,
    isExpanded: Boolean,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "addButtonRotation"
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            inputColors.inputIcon.copy(alpha = 0.7f)
        },
        animationSpec = tween(200),
        label = "addButtonColor"
    )

    Surface(
        onClick = onClick,
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = buttonColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.attach_file),
                tint = if (isExpanded) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    inputColors.inputFieldBackground
                },
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}
