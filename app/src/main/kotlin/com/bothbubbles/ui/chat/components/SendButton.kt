package com.bothbubbles.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.util.HapticUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bothbubbles.core.design.theme.AppTextStyles
import com.bothbubbles.R
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * MD3 Squircle corner radius for the send button.
 * Uses 16dp to match FAB styling per Material Design 3 guidelines.
 */
private val SendButtonCornerRadius = 16.dp

/**
 * Send button with protocol-based coloring and MD3 squircle shape.
 * Green background for SMS/MMS, blue for iMessage.
 * Long press opens the effect picker for iMessage.
 *
 * MD3 Features:
 * - Squircle shape (rounded rectangle with 16dp radius)
 * - Mode-specific icon badging for accessibility
 * - Harmonized theme colors
 *
 * @param isSending Whether a send operation is in progress
 * @param sendMode The current send mode (SMS or IMESSAGE)
 * @param onClick Callback when the button is tapped
 * @param onLongClick Callback when the button is long-pressed (for effects)
 * @param modifier Modifier for the button
 * @param isMmsMode Whether MMS mode is active (shows MMS badge)
 * @param showEffectHint Whether to hint at long-press for effects (iMessage only)
 */
@Composable
fun SendButton(
    isSending: Boolean,
    sendMode: ChatSendMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMmsMode: Boolean = false,
    showEffectHint: Boolean = false
) {
    val isSmsMode = sendMode == ChatSendMode.SMS

    // Protocol-based coloring using theme colors (harmonized with dynamic color)
    val bubbleColors = BothBubblesTheme.bubbleColors
    val containerColor by animateColorAsState(
        targetValue = if (isSmsMode) bubbleColors.sendButtonSms else bubbleColors.sendButtonIMessage,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "sendButtonColor"
    )
    val contentColor = Color.White

    // Press feedback animation
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !isSending) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "sendButtonScale"
    )

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .height(40.dp)
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(SendButtonCornerRadius))
            .background(
                if (isSending) containerColor.copy(alpha = 0.38f) else containerColor
            )
            .pointerInput(!isSending) {
                if (!isSending) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = {
                            HapticUtils.onTap(haptic)
                            onClick()
                        },
                        onLongPress = {
                            HapticUtils.onLongPress(haptic)
                            onLongClick()
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSending) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            if (isMmsMode) {
                // Show MMS label below icon for SMS mode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send_message),
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "MMS",
                        style = AppTextStyles.badgeMicro,
                        color = contentColor
                    )
                }
            } else {
                // SMS mode or iMessage mode: Just show the send icon
                // The green/blue button color already indicates the mode
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ====================
// Preview Functions
// ====================

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Send Button - iMessage")
@Composable
private fun SendButtonIMessagePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            SendButton(
                onClick = {},
                onLongClick = {},
                isSending = false,
                sendMode = ChatSendMode.IMESSAGE
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Send Button - SMS")
@Composable
private fun SendButtonSmsPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            SendButton(
                onClick = {},
                onLongClick = {},
                isSending = false,
                sendMode = ChatSendMode.SMS
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Send Button - MMS")
@Composable
private fun SendButtonMmsPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            SendButton(
                onClick = {},
                onLongClick = {},
                isSending = false,
                sendMode = ChatSendMode.SMS,
                isMmsMode = true
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Send Button - Sending")
@Composable
private fun SendButtonSendingPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            SendButton(
                onClick = {},
                onLongClick = {},
                isSending = true,
                sendMode = ChatSendMode.IMESSAGE
            )
        }
    }
}
