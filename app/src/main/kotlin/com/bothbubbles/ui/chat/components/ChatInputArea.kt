package com.bothbubbles.ui.chat.components

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.bothbubbles.R
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.ui.chat.AttachmentWarning
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.SendButtonAnimationPhase
import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.ui.theme.BubbleColors

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
    val bubbleColors = BothBubblesTheme.bubbleColors

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
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = inputColors.inputText.copy(alpha = 0.7f)
                            )

                            // Quality indicator button - shown only when there are image attachments
                            if (hasCompressibleImages) {
                                QualityIndicator(
                                    currentQuality = currentImageQuality,
                                    onClick = onQualityClick
                                )
                            }
                        }

                        if (attachments.size > 1) {
                            TextButton(
                                onClick = onClearAllAttachments,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Clear All",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    ReorderableAttachmentStrip(
                        attachments = attachments,
                        onRemove = onRemoveAttachment,
                        onEdit = onEditAttachment,
                        onReorder = onReorderAttachments,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Quality indicator (only if compressible images present)
                    if (hasCompressibleImages) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                onClick = onQualityClick,
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = currentImageQuality.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Attachment size warning banner
            AnimatedVisibility(
                visible = attachmentWarning != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                attachmentWarning?.let { warning ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (warning.isError)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.tertiaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (warning.isError)
                                        Icons.Default.ErrorOutline
                                    else
                                        Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (warning.isError)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = warning.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (warning.isError)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (warning.isError && warning.affectedUri != null) {
                                    TextButton(
                                        onClick = onRemoveWarningAttachment,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = "Remove",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                } else if (!warning.isError) {
                                    TextButton(
                                        onClick = onDismissWarning,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = "Dismiss",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

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
                            // Normal text input field with mode-based tinting
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
                                modifier = Modifier
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
                                    val addButtonRotation by animateFloatAsState(
                                        targetValue = if (isPickerExpanded) 45f else 0f,
                                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                                        label = "addButtonRotation"
                                    )
                                    val addButtonColor by animateColorAsState(
                                        targetValue = if (isPickerExpanded) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            inputColors.inputIcon.copy(alpha = 0.7f)
                                        },
                                        animationSpec = tween(200),
                                        label = "addButtonColor"
                                    )
                                    Surface(
                                        onClick = onAttachClick,
                                        modifier = Modifier.size(28.dp),
                                        shape = CircleShape,
                                        color = addButtonColor
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(R.string.attach_file),
                                                tint = if (isPickerExpanded) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    inputColors.inputFieldBackground
                                                },
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .graphicsLayer { rotationZ = addButtonRotation }
                                            )
                                        }
                                    }

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
                        InputMode.RECORDING -> {
                            // Expanded recording panel with controls
                            ExpandedRecordingPanel(
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
                            // Preview/playback controls
                            PreviewContent(
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
 * Recording mode center content with pulsing indicator and waveform
 */
@Composable
internal fun ExpandedRecordingPanel(
    duration: Long,
    amplitudeHistory: List<Float>,
    isNoiseCancellationEnabled: Boolean,
    onNoiseCancellationToggle: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timer row with pulsing dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Pulsing red recording dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = Color.Red.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.headlineMedium,
                    color = inputColors.inputText
                )
            }

            // Waveform visualization - larger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                amplitudeHistory.forEachIndexed { index, amplitude ->
                    val targetHeight = (8f + amplitude * 48f).coerceIn(8f, 56f)
                    val animatedHeight by animateFloatAsState(
                        targetValue = targetHeight,
                        animationSpec = spring(
                            dampingRatio = 0.6f,
                            stiffness = 400f
                        ),
                        label = "bar_$index"
                    )

                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(animatedHeight.dp)
                            .background(
                                color = Color.Red.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            // Noise cancellation toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = inputColors.inputText.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.noise_cancellation) + " " +
                           if (isNoiseCancellationEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.bodySmall,
                    color = inputColors.inputText.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isNoiseCancellationEnabled,
                    onCheckedChange = { onNoiseCancellationToggle() },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // Bottom controls row: Cancel, Restart, Stop, Attach
            // Using icon-only buttons for Cancel/Restart to save space
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel button (icon-only)
                Surface(
                    onClick = onCancel,
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Restart button (icon-only)
                Surface(
                    onClick = onRestart,
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.restart_recording),
                            tint = inputColors.inputIcon,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Red stop button (prominent)
                Surface(
                    onClick = onStop,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = Color.Red
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Stop square icon
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    }
                }

                // Attach/Done button (pill shape with checkmark)
                Surface(
                    onClick = onAttach,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Done",
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Preview mode center content with playback controls
 */
@Composable
internal fun PreviewContent(
    duration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onCancel: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val formattedPosition = remember(playbackPosition) {
        val seconds = (playbackPosition / 1000) % 60
        val minutes = (playbackPosition / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val progress = if (duration > 0) (playbackPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Time display
            Text(
                text = if (isPlaying) formattedPosition else formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = inputColors.inputText,
                modifier = Modifier.width(36.dp)
            )

            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Re-record button
            IconButton(
                onClick = onReRecord,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = "Re-record",
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// SendButton is now extracted to SendButton.kt

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
