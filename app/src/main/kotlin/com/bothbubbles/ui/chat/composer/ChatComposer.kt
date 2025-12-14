package com.bothbubbles.ui.chat.composer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.components.ExpandedRecordingPanel
import com.bothbubbles.ui.chat.components.PreviewContent
import com.bothbubbles.ui.chat.components.SendButton
import com.bothbubbles.ui.chat.components.VoiceMemoButton
import com.bothbubbles.ui.chat.composer.components.AttachmentThumbnailRow
import com.bothbubbles.ui.chat.composer.components.ComposerActionButtons
import com.bothbubbles.ui.chat.composer.components.ComposerMediaButtons
import com.bothbubbles.ui.chat.composer.components.ComposerSendButton
import com.bothbubbles.ui.chat.composer.components.ComposerTextField
import com.bothbubbles.ui.chat.composer.components.ReplyPreviewBar
import com.bothbubbles.ui.chat.composer.components.SmartReplyRow
import com.bothbubbles.ui.chat.composer.panels.ComposerPanelHost
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Main chat composer component following Google Messages design patterns.
 *
 * This is the orchestrating component that combines:
 * - Text input field with dynamic placeholder
 * - Left-side action button (add attachments)
 * - Right-side media buttons (camera, emoji, gallery)
 * - Send/voice memo button
 * - Attachment preview strip
 * - Recording and preview modes
 *
 * Layout structure (Google Messages style):
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │ [Attachment Thumbnails Row - if attachments selected]        │
 * ├──────────────────────────────────────────────────────────────┤
 * │ ╭────────────────────────────────────────────────────────╮   │
 * │ │ [+] │ Message text input...            [📷] [😊] [🖼] │   │
 * │ ╰────────────────────────────────────────────────────────╯ ▶ │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * @param state Current composer state
 * @param onEvent Callback for handling composer events
 * @param modifier Modifier for the composer container
 */
@Composable
fun ChatComposer(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    onMediaSelected: (List<Uri>) -> Unit = { uris -> onEvent(ComposerEvent.AddAttachments(uris)) },
    onCameraClick: () -> Unit = { onEvent(ComposerEvent.OpenCamera) },
    onFileClick: () -> Unit = { /* TODO: Implement file picker */ },
    onLocationClick: () -> Unit = { /* TODO: Implement location sharing */ },
    onContactClick: () -> Unit = { /* TODO: Implement contact sharing */ },
    // GIF Picker callbacks
    gifPickerState: com.bothbubbles.ui.chat.composer.panels.GifPickerState = com.bothbubbles.ui.chat.composer.panels.GifPickerState.Idle,
    gifSearchQuery: String = "",
    onGifSearchQueryChange: (String) -> Unit = {},
    onGifSearch: (String) -> Unit = {},
    onGifSelected: (com.bothbubbles.ui.chat.composer.panels.GifItem) -> Unit = {},
    onSendButtonBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors

    // Photo picker launcher for direct gallery access from image icon
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onMediaSelected(uris)
        }
    }

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
            // Reply preview bar
            ReplyPreviewBar(
                replyTo = state.replyToMessage,
                onDismiss = { onEvent(ComposerEvent.DismissReply) }
            )

            // Attachment thumbnails row
            if (state.inputMode == ComposerInputMode.TEXT) {
                AttachmentThumbnailRow(
                    attachments = state.attachments,
                    onRemove = { onEvent(ComposerEvent.RemoveAttachment(it)) },
                    onEdit = { onEvent(ComposerEvent.EditAttachment(it)) },
                    onClearAll = { onEvent(ComposerEvent.ClearAllAttachments) },
                    onQualityClick = { onEvent(ComposerEvent.OpenQualitySheet) },
                    currentQuality = state.currentImageQuality.name.lowercase()
                        .replaceFirstChar { it.uppercase() }
                )
            }

            // Smart reply suggestions
            SmartReplyRow(
                replies = state.smartReplies,
                visible = state.showSmartReplies && state.inputMode == ComposerInputMode.TEXT,
                onReplyClick = { onEvent(ComposerEvent.SelectSmartReply(it)) }
            )

            // Main input row
            MainInputRow(
                state = state,
                onEvent = onEvent,
                onGalleryClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSendButtonBoundsChanged = onSendButtonBoundsChanged
            )

            // Expandable panels (Media picker, Emoji, GIF)
            ComposerPanelHost(
                activePanel = state.activePanel,
                onMediaSelected = onMediaSelected,
                onCameraClick = onCameraClick,
                onGifClick = { onEvent(ComposerEvent.ToggleGifPicker) },
                onFileClick = onFileClick,
                onLocationClick = onLocationClick,
                onAudioClick = { onEvent(ComposerEvent.StartVoiceRecording) },
                onContactClick = onContactClick,
                onEmojiSelected = { emoji ->
                    onEvent(ComposerEvent.TextChanged(state.text + emoji))
                },
                gifPickerState = gifPickerState,
                gifSearchQuery = gifSearchQuery,
                onGifSearchQueryChange = onGifSearchQueryChange,
                onGifSearch = onGifSearch,
                onGifSelected = onGifSelected,
                onDismiss = { onEvent(ComposerEvent.DismissPanel) }
            )
        }
    }
}

/**
 * Main input row with text field and action buttons.
 */
@Composable
private fun MainInputRow(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    onGalleryClick: () -> Unit,
    onSendButtonBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {}
) {
    val inputColors = BothBubblesTheme.bubbleColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Cancel button for preview mode
        AnimatedVisibility(
            visible = state.inputMode == ComposerInputMode.VOICE_PREVIEW,
            enter = fadeIn(tween(150)) + slideInHorizontally { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutHorizontally { -it / 2 }
        ) {
            // Cancel button handled in PreviewContent
        }

        // Center content: Text field, Recording panel, or Preview
        AnimatedContent(
            targetState = state.inputMode,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInHorizontally { it / 3 })
                    .togetherWith(fadeOut(tween(150)) + slideOutHorizontally { -it / 3 })
            },
            modifier = Modifier.weight(1f),
            label = "composer_center_content"
        ) { inputMode ->
            when (inputMode) {
                ComposerInputMode.TEXT -> {
                    TextInputContent(
                        state = state,
                        onEvent = onEvent,
                        onGalleryClick = onGalleryClick
                    )
                }
                ComposerInputMode.VOICE_RECORDING -> {
                    val recordingState = state.recordingState ?: RecordingState()
                    ExpandedRecordingPanel(
                        duration = recordingState.durationMs,
                        amplitudeHistory = recordingState.amplitudeHistory,
                        isNoiseCancellationEnabled = recordingState.isNoiseCancellationEnabled,
                        onNoiseCancellationToggle = { onEvent(ComposerEvent.ToggleNoiseCancellation) },
                        onStop = { onEvent(ComposerEvent.StopVoiceRecording) },
                        onRestart = { onEvent(ComposerEvent.RestartVoiceRecording) },
                        onAttach = { onEvent(ComposerEvent.SendVoiceMemo) },
                        onCancel = { onEvent(ComposerEvent.CancelVoiceRecording) },
                        inputColors = inputColors
                    )
                }
                ComposerInputMode.VOICE_PREVIEW -> {
                    val recordingState = state.recordingState ?: RecordingState()
                    PreviewContent(
                        duration = recordingState.durationMs,
                        playbackPosition = recordingState.playbackPositionMs,
                        isPlaying = recordingState.isPlaying,
                        onPlayPause = { onEvent(ComposerEvent.TogglePreviewPlayback) },
                        onReRecord = { onEvent(ComposerEvent.ReRecordVoiceMemo) },
                        onCancel = { onEvent(ComposerEvent.CancelVoiceRecording) },
                        inputColors = inputColors
                    )
                }
            }
        }

        // Right side: Send/Voice button (hidden during recording)
        AnimatedVisibility(
            visible = state.inputMode != ComposerInputMode.VOICE_RECORDING,
            enter = fadeIn(tween(150)) + slideInHorizontally { it / 2 },
            exit = fadeOut(tween(150)) + slideOutHorizontally { it / 2 }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(6.dp))

                ActionButton(
                    state = state,
                    onEvent = onEvent,
                    onSendButtonBoundsChanged = onSendButtonBoundsChanged
                )
            }
        }
    }
}

/**
 * Text input content with add button, text field, and media buttons.
 */
@Composable
private fun TextInputContent(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    onGalleryClick: () -> Unit
) {
    ComposerTextField(
        text = state.text,
        onTextChange = { onEvent(ComposerEvent.TextChanged(it)) },
        sendMode = state.sendMode,
        isEnabled = !state.smsInputBlocked,
        onSmsBlockedClick = { onEvent(ComposerEvent.SmsInputBlockedTapped) },
        onFocusChanged = { onEvent(ComposerEvent.TextFieldFocusChanged(it)) },
        leadingContent = {
            ComposerActionButtons(
                isExpanded = state.isPickerExpanded,
                onClick = { onEvent(ComposerEvent.ToggleMediaPicker) }
            )
        },
        trailingContent = {
            ComposerMediaButtons(
                showCamera = state.text.isBlank(),
                onCameraClick = { onEvent(ComposerEvent.OpenCamera) },
                onImageClick = onGalleryClick,
                onEmojiClick = { onEvent(ComposerEvent.ToggleEmojiPicker) }
            )
        }
    )
}

/**
 * Send or voice memo action button.
 *
 * Uses the new ComposerSendButton when mode toggle is available,
 * with the extracted gesture handler for cleaner separation of concerns.
 */
@Composable
private fun ActionButton(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    onSendButtonBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {}
) {
    val showSend = when {
        state.tutorialState.isVisible -> true
        state.inputMode == ComposerInputMode.VOICE_PREVIEW -> true
        state.canSend -> true
        else -> false
    }

    Crossfade(
        targetState = showSend,
        label = "action_button"
    ) { shouldShowSend ->
        if (shouldShowSend) {
            // Use toggle button when mode toggle is available
            if (state.inputMode == ComposerInputMode.TEXT && state.canToggleSendMode) {
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        onSendButtonBoundsChanged(coordinates.boundsInRoot())
                    }
                ) {
                    ComposerSendButton(
                        onClick = { onEvent(ComposerEvent.Send) },
                        onLongPress = { onEvent(ComposerEvent.SendLongPress) },
                        currentMode = state.sendMode,
                        canToggle = state.canToggleSendMode,
                        onModeToggle = { mode ->
                            onEvent(ComposerEvent.ToggleSendMode(mode))
                            true
                        },
                        isSending = state.isSending,
                        isMmsMode = state.isMmsMode,
                        showRevealAnimation = state.isSendModeAnimating,
                        tutorialState = state.tutorialState,
                        onAnimationPhaseChange = { phase ->
                            if (phase == SendButtonPhase.IDLE) {
                                onEvent(ComposerEvent.RevealAnimationComplete)
                            }
                        }
                    )
                }
            } else {
                // Regular send button for preview or when toggle not available
                SendButton(
                    onClick = {
                        when (state.inputMode) {
                            ComposerInputMode.VOICE_PREVIEW -> onEvent(ComposerEvent.SendVoiceMemo)
                            else -> onEvent(ComposerEvent.Send)
                        }
                    },
                    onLongPress = {
                        if (state.inputMode == ComposerInputMode.TEXT) {
                            onEvent(ComposerEvent.SendLongPress)
                        }
                    },
                    isSending = state.isSending && state.inputMode == ComposerInputMode.TEXT,
                    isSmsMode = state.isSmsMode,
                    isMmsMode = state.isMmsMode && state.inputMode == ComposerInputMode.TEXT,
                    showEffectHint = !state.isSmsMode && state.inputMode == ComposerInputMode.TEXT
                )
            }
        } else {
            VoiceMemoButton(
                onClick = { onEvent(ComposerEvent.VoiceMemoTap) },
                onPressStart = { onEvent(ComposerEvent.StartVoiceRecording) },
                onPressEnd = { onEvent(ComposerEvent.StopVoiceRecording) },
                isSmsMode = state.isSmsMode,
                isDisabled = state.smsInputBlocked
            )
        }
    }
}
