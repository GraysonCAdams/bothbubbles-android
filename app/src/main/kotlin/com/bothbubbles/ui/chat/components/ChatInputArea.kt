package com.bothbubbles.ui.chat.components

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bothbubbles.R
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.ui.chat.AttachmentWarning
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.SendButtonAnimationPhase
import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Input mode for the chat input area.
 */
enum class InputMode {
    NORMAL,     // Standard text input
    RECORDING,  // Voice memo recording in progress
    PREVIEW     // Voice memo preview/playback
}

/**
 * Unified input area that handles all three input modes (normal, recording, preview)
 * with smooth animated transitions and consistent dimensions.
 */
@Composable
fun ChatInputArea(
    mode: InputMode,
    // Normal mode props
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onSendLongPress: () -> Unit,
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onImageClick: () -> Unit,
    onVoiceMemoClick: () -> Unit,
    onVoiceMemoPressStart: () -> Unit,
    onVoiceMemoPressEnd: () -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    currentSendMode: ChatSendMode,
    smsInputBlocked: Boolean,
    onSmsInputBlockedClick: () -> Unit,
    hasAttachments: Boolean,
    attachments: List<PendingAttachmentInput>,
    onRemoveAttachment: (Uri) -> Unit,
    onClearAllAttachments: () -> Unit,
    onEditAttachment: (Uri) -> Unit = {},
    onReorderAttachments: (List<PendingAttachmentInput>) -> Unit = {},
    isPickerExpanded: Boolean,
    // Image quality props
    hasCompressibleImages: Boolean = false,
    currentImageQuality: AttachmentQuality = AttachmentQuality.STANDARD,
    onQualityClick: () -> Unit = {},
    // Attachment warning props
    attachmentWarning: AttachmentWarning? = null,
    onDismissWarning: () -> Unit = {},
    onRemoveWarningAttachment: () -> Unit = {},
    // Recording mode props
    recordingDuration: Long,
    amplitudeHistory: List<Float>,
    onRecordingCancel: () -> Unit,
    onRecordingSend: () -> Unit,
    isNoiseCancellationEnabled: Boolean = true,
    onNoiseCancellationToggle: () -> Unit = {},
    onRecordingStop: () -> Unit = {},
    onRecordingRestart: () -> Unit = {},
    // Preview mode props
    previewDuration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onPreviewSend: () -> Unit,
    onPreviewCancel: () -> Unit,
    // Send mode toggle props
    canToggleSendMode: Boolean = false,
    showSendModeRevealAnimation: Boolean = false,
    tutorialState: TutorialState = TutorialState.NOT_SHOWN,
    onModeToggle: (ChatSendMode) -> Boolean = { false },
    onRevealAnimationComplete: () -> Unit = {},
    onSendButtonBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // MMS mode is for local SMS chats when attachments or long text is present
    val isMmsMode = isLocalSmsChat && (hasAttachments || text.length > 160)
    val hasContent = text.isNotBlank() || hasAttachments
    // Use currentSendMode for UI coloring (SMS = green, iMessage = blue)
    val isSmsMode = currentSendMode == ChatSendMode.SMS
    val inputColors = BothBubblesTheme.bubbleColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            // Attachment previews (only in normal mode)
            AnimatedVisibility(
                visible = mode == InputMode.NORMAL && attachments.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ChatAttachmentStrip(
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                    onClearAllAttachments = onClearAllAttachments,
                    onEditAttachment = onEditAttachment,
                    onReorderAttachments = onReorderAttachments,
                    hasCompressibleImages = hasCompressibleImages,
                    currentImageQuality = currentImageQuality,
                    onQualityClick = onQualityClick,
                    inputColors = inputColors
                )
            }

            // Attachment size warning banner
            AttachmentWarningBanner(
                warning = attachmentWarning,
                onDismiss = onDismissWarning,
                onRemoveAttachment = onRemoveWarningAttachment
            )

            // Main input row with fixed structure
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side cancel button - only visible in preview mode (recording has controls in panel)
                AnimatedVisibility(
                    visible = mode == InputMode.PREVIEW,
                    enter = fadeIn(animationSpec = tween(150)) +
                        slideInHorizontally(animationSpec = tween(200)) { -it / 2 },
                    exit = fadeOut(animationSpec = tween(150)) +
                        slideOutHorizontally(animationSpec = tween(200)) { -it / 2 }
                ) {
                    Row {
                        // Cancel button for preview mode
                        Surface(
                            onClick = onPreviewCancel,
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                // Center content - animated between text field / recording / preview
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) +
                            slideInHorizontally(animationSpec = tween(250)) { it / 3 })
                            .togetherWith(fadeOut(animationSpec = tween(150)) +
                                slideOutHorizontally(animationSpec = tween(200)) { -it / 3 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "center_content"
                ) { currentMode ->
                    when (currentMode) {
                        InputMode.NORMAL -> {
                            ChatInputFieldRow(
                                text = text,
                                onTextChange = onTextChange,
                                onAttachClick = onAttachClick,
                                onEmojiClick = onEmojiClick,
                                onImageClick = onImageClick,
                                currentSendMode = currentSendMode,
                                isPickerExpanded = isPickerExpanded,
                                smsInputBlocked = smsInputBlocked,
                                onSmsInputBlockedClick = onSmsInputBlockedClick,
                                inputColors = inputColors
                            )
                        }
                        InputMode.RECORDING -> {
                            ChatRecordingPanel(
                                duration = recordingDuration,
                                amplitudeHistory = amplitudeHistory,
                                isNoiseCancellationEnabled = isNoiseCancellationEnabled,
                                onNoiseCancellationToggle = onNoiseCancellationToggle,
                                onStop = onRecordingStop,
                                onRestart = onRecordingRestart,
                                onAttach = onRecordingSend,
                                onCancel = onRecordingCancel,
                                inputColors = inputColors
                            )
                        }
                        InputMode.PREVIEW -> {
                            ChatPreviewPanel(
                                duration = previewDuration,
                                playbackPosition = playbackPosition,
                                isPlaying = isPlaying,
                                onPlayPause = onPlayPause,
                                onReRecord = onReRecord,
                                onCancel = onPreviewCancel,
                                inputColors = inputColors
                            )
                        }
                    }
                }

                // Hide right side buttons during RECORDING mode (controls are in the expanded panel)
                AnimatedVisibility(
                    visible = mode != InputMode.RECORDING,
                    enter = fadeIn(animationSpec = tween(150)) +
                        slideInHorizontally(animationSpec = tween(200)) { it / 2 },
                    exit = fadeOut(animationSpec = tween(150)) +
                        slideOutHorizontally(animationSpec = tween(200)) { it / 2 }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(6.dp))

                        // Right side action button - voice memo or send
                        Crossfade(
                            targetState = when {
                                tutorialState == TutorialState.STEP_1_SWIPE_UP ||
                                        tutorialState == TutorialState.STEP_2_SWIPE_BACK -> true
                                mode == InputMode.PREVIEW -> true
                                hasContent -> true
                                else -> false
                            },
                            label = "action_button"
                        ) { showSend ->
                            if (showSend) {
                                // Use toggle button in normal mode when toggle is available
                                if (mode == InputMode.NORMAL && canToggleSendMode) {
                                    Box(
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            onSendButtonBoundsChanged(coordinates.boundsInRoot())
                                        }
                                    ) {
                                        SendModeToggleButton(
                                            onClick = onSendClick,
                                            onLongPress = onSendLongPress,
                                            currentMode = currentSendMode,
                                            canToggle = canToggleSendMode,
                                            onModeToggle = onModeToggle,
                                            isSending = isSending,
                                            isMmsMode = isMmsMode,
                                            showRevealAnimation = showSendModeRevealAnimation,
                                            tutorialActive = tutorialState == TutorialState.STEP_1_SWIPE_UP ||
                                                    tutorialState == TutorialState.STEP_2_SWIPE_BACK,
                                            onAnimationConfigChange = { config ->
                                                // Mark animation as complete when it finishes
                                                if (config.phase == SendButtonAnimationPhase.IDLE) {
                                                    onRevealAnimationComplete()
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    // Fall back to regular send button for preview or when toggle not available
                                    SendButton(
                                        isSending = isSending && mode == InputMode.NORMAL,
                                        sendMode = currentSendMode,
                                        onClick = when (mode) {
                                            InputMode.PREVIEW -> onPreviewSend
                                            else -> onSendClick
                                        },
                                        onLongClick = if (mode == InputMode.NORMAL) onSendLongPress else { {} },
                                        isMmsMode = isMmsMode && mode == InputMode.NORMAL,
                                        showEffectHint = !isSmsMode && mode == InputMode.NORMAL
                                    )
                                }
                            } else {
                                VoiceMemoButton(
                                    onClick = onVoiceMemoClick,
                                    onPressStart = onVoiceMemoPressStart,
                                    onPressEnd = onVoiceMemoPressEnd,
                                    isSmsMode = isSmsMode,
                                    isDisabled = smsInputBlocked
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Corner radius constant for VoiceMemoButton (matches SendButton).
 */
private val VoiceMemoButtonCornerRadius = 16.dp

/**
 * Voice memo button with soundwave icon and MD3 squircle shape.
 * Protocol-colored: green for SMS, blue for iMessage.
 * Hold to record, release to stop. Tap requests permission only.
 *
 * MD3 Features:
 * - Squircle shape matching send button
 * - Harmonized theme colors
 */
@Composable
internal fun VoiceMemoButton(
    onClick: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    isSmsMode: Boolean,
    isDisabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Protocol-based coloring using theme colors (harmonized with dynamic color)
    val bubbleColors = BothBubblesTheme.bubbleColors
    val containerColor by animateColorAsState(
        targetValue = when {
            isDisabled -> Color.Gray.copy(alpha = 0.3f)
            isSmsMode -> bubbleColors.sendButtonSms
            else -> bubbleColors.sendButtonIMessage
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "voiceMemoButtonColor"
    )

    Box(
        modifier = modifier
            .height(40.dp)
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(VoiceMemoButtonCornerRadius))
            .background(containerColor)
            .then(
                if (isDisabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        // Wait for initial press
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        // Track timing and state
                        val holdThresholdMs = 200L
                        val pressStartTime = System.currentTimeMillis()
                        var recordingStarted = false

                        // Wait for finger lift while tracking hold duration
                        do {
                            val event = awaitPointerEvent()
                            val elapsed = System.currentTimeMillis() - pressStartTime

                            // Start recording once hold threshold is reached
                            if (elapsed >= holdThresholdMs && !recordingStarted) {
                                recordingStarted = true
                                onPressStart()
                            }

                            // Consume all changes to prevent event leaking
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })

                        // Finger lifted
                        if (recordingStarted) {
                            // Was recording - stop it
                            onPressEnd()
                        } else {
                            // Quick tap - just request permission
                            onClick()
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(R.string.voice_memo),
            modifier = Modifier.size(20.dp),
            tint = if (isDisabled) Color.White.copy(alpha = 0.4f) else Color.White
        )
    }
}

// ====================
// Preview Functions
// ====================

// SendButton previews are now in SendButton.kt

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Voice Memo Button - iMessage")
@Composable
private fun VoiceMemoButtonPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            VoiceMemoButton(
                onClick = {},
                onPressStart = {},
                onPressEnd = {},
                isSmsMode = false
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Voice Memo Button - SMS")
@Composable
private fun VoiceMemoButtonSmsPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Box(modifier = Modifier.height(40.dp).padding(8.dp)) {
            VoiceMemoButton(
                onClick = {},
                onPressStart = {},
                onPressEnd = {},
                isSmsMode = true
            )
        }
    }
}
