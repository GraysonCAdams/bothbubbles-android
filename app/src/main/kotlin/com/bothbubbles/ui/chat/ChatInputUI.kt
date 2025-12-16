package com.bothbubbles.ui.chat

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.ui.chat.components.InputMode
import com.bothbubbles.ui.chat.components.ReplyPreview
import com.bothbubbles.ui.chat.composer.ChatComposer
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerInputMode
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.RecordingState
import com.bothbubbles.ui.chat.composer.panels.GifItem
import com.bothbubbles.ui.chat.composer.panels.GifPickerState
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.components.input.AttachmentPickerPanel
import com.bothbubbles.ui.components.input.EmojiPickerPanel
import com.bothbubbles.ui.components.input.SmartReplyChips
import com.bothbubbles.ui.components.input.SuggestionItem
import com.bothbubbles.ui.components.message.MessageUiModel

/**
 * Extracted input area component containing:
 * - AttachmentPickerPanel (slides up above input)
 * - EmojiPickerPanel (slides up above input)
 * - SmartReplyChips
 * - ReplyPreview
 * - ChatComposer
 *
 * This extraction follows Stage 2 of the refactor plan to declutter ChatScreen.kt
 */
@Composable
fun ChatInputUI(
    // Composer state from delegate
    composerState: ComposerState,
    // Audio state for voice recording
    audioState: ChatAudioState,
    // Send state for reply tracking
    sendState: SendState,
    // Smart reply suggestions
    smartReplySuggestions: List<SuggestionItem>,
    // Pre-computed reply target message (avoids passing full messages list)
    replyingToMessage: MessageUiModel?,
    // Whether this is a local SMS chat (affects long-press behavior)
    isLocalSmsChat: Boolean,
    // Picker visibility state
    showAttachmentPicker: Boolean,
    showEmojiPicker: Boolean,
    // GIF picker state
    gifPickerState: GifPickerState,
    gifSearchQuery: String,
    // Callbacks - Picker dismissal
    onDismissAttachmentPicker: () -> Unit,
    onDismissEmojiPicker: () -> Unit,
    // Callbacks - Attachment handling
    onAttachmentSelected: (android.net.Uri) -> Unit,
    onLocationSelected: (Double, Double) -> Unit,
    onContactSelected: (android.net.Uri) -> Unit,
    onScheduleClick: () -> Unit,
    onCameraClick: () -> Unit,
    // Callbacks - Emoji handling
    onEmojiSelected: (String) -> Unit,
    // Callbacks - Smart reply
    onSmartReplyClick: (SuggestionItem) -> Unit,
    // Callbacks - Reply preview
    onCancelReply: () -> Unit,
    // Callbacks - Composer events
    onComposerEvent: (ComposerEvent) -> Unit,
    onMediaSelected: (List<android.net.Uri>) -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onContactClick: () -> Unit,
    // Callbacks - GIF picker
    onGifSearchQueryChange: (String) -> Unit,
    onGifSearch: (String) -> Unit,
    onGifSelected: (GifItem) -> Unit,
    // Callbacks - Effect picker trigger
    onShowEffectPicker: () -> Unit,
    // Callbacks - Quality sheet trigger
    onShowQualitySheet: () -> Unit,
    // Callbacks - Edit attachment
    onEditAttachmentClick: (android.net.Uri) -> Unit,
    // Callbacks - Voice memo send
    onSendVoiceMemo: (android.net.Uri) -> Unit,
    // Callbacks - Send button bounds for tutorial
    onSendButtonBoundsChanged: (Rect) -> Unit,
    // Modifier for size tracking
    modifier: Modifier = Modifier,
    onSizeChanged: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    // Debug: Track recomposition with granular timing
    val t0 = System.currentTimeMillis()
    Log.d("PerfTrace", "ChatInputUI START, replyingTo=${replyingToMessage?.guid}, smartReplies=${smartReplySuggestions.size}")

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            audioState.startRecording(context, hapticFeedback)
        } else {
            Toast.makeText(context, "Microphone permission required for voice memos", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .onSizeChanged { size -> onSizeChanged(size.height) }
    ) {
        // Attachment picker panel (slides up above input)
        AttachmentPickerPanel(
            visible = showAttachmentPicker,
            onDismiss = onDismissAttachmentPicker,
            onAttachmentSelected = onAttachmentSelected,
            onLocationSelected = onLocationSelected,
            onContactSelected = onContactSelected,
            onScheduleClick = onScheduleClick,
            onCameraClick = onCameraClick
        )

        // Emoji picker panel (slides up above input)
        EmojiPickerPanel(
            visible = showEmojiPicker,
            onDismiss = onDismissEmojiPicker,
            onEmojiSelected = { emoji ->
                onEmojiSelected(emoji)
            }
        )

        // Determine input mode for unified handling
        val inputMode = when {
            audioState.isRecording -> InputMode.RECORDING
            audioState.isPreviewingVoiceMemo -> InputMode.PREVIEW
            else -> InputMode.NORMAL
        }

        // Smart reply chips - hide during recording/preview with animation
        AnimatedVisibility(
            visible = inputMode == InputMode.NORMAL,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            SmartReplyChips(
                suggestions = smartReplySuggestions,
                onSuggestionClick = onSmartReplyClick
            )
        }

        // Reply preview - shows when replying to a message (passed in pre-computed)
        AnimatedVisibility(
            visible = replyingToMessage != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            replyingToMessage?.let { message ->
                ReplyPreview(
                    message = message,
                    onDismiss = onCancelReply
                )
            }
        }

        // Merge recording state when actively recording/previewing
        // Note: composerState must be a key so changes propagate
        val adjustedComposerState = remember(
            composerState,
            audioState.isRecording,
            audioState.isPreviewingVoiceMemo,
            audioState.recordingDuration,
            audioState.playbackPosition,
            audioState.isPlayingVoiceMemo
        ) {
            if (audioState.isRecording || audioState.isPreviewingVoiceMemo) {
                composerState.copy(
                    inputMode = if (audioState.isRecording) ComposerInputMode.VOICE_RECORDING else ComposerInputMode.VOICE_PREVIEW,
                    recordingState = RecordingState(
                        durationMs = audioState.recordingDuration,
                        amplitudeHistory = audioState.amplitudeHistory,
                        isNoiseCancellationEnabled = audioState.isNoiseCancellationEnabled,
                        playbackPositionMs = audioState.playbackPosition,
                        isPlaying = audioState.isPlayingVoiceMemo,
                        recordedUri = audioState.getRecordingUri()
                    )
                )
            } else {
                composerState.copy(inputMode = ComposerInputMode.TEXT)
            }
        }

        Log.d("PerfTrace", "Before ChatComposer: +${System.currentTimeMillis() - t0}ms")
        ChatComposer(
            state = adjustedComposerState,
            onEvent = { event ->
                when (event) {
                    is ComposerEvent.OpenCamera -> onCameraClick()
                    is ComposerEvent.SendLongPress -> {
                        if (!isLocalSmsChat) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShowEffectPicker()
                        }
                    }
                    is ComposerEvent.OpenQualitySheet -> onShowQualitySheet()
                    is ComposerEvent.EditAttachment -> onEditAttachmentClick(event.attachment.uri)
                    // Voice recording events - delegated to ChatAudioState
                    is ComposerEvent.StartVoiceRecording, is ComposerEvent.VoiceMemoTap -> {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    is ComposerEvent.StopVoiceRecording -> {
                        audioState.stopRecording(hapticFeedback)
                    }
                    is ComposerEvent.CancelVoiceRecording -> {
                        audioState.cancelRecording()
                    }
                    is ComposerEvent.RestartVoiceRecording -> {
                        audioState.restartRecording(context, hapticFeedback)
                    }
                    is ComposerEvent.ToggleNoiseCancellation -> {
                        audioState.toggleNoiseCancellation()
                    }
                    is ComposerEvent.TogglePreviewPlayback -> {
                        audioState.togglePlayback()
                    }
                    is ComposerEvent.ReRecordVoiceMemo -> {
                        audioState.reRecordFromPreview(context, hapticFeedback)
                    }
                    is ComposerEvent.SendVoiceMemo -> {
                        // Add voice memo as attachment and send
                        audioState.getRecordingUri()?.let { uri ->
                            onSendVoiceMemo(uri)
                        }
                        audioState.resetAfterSend()
                    }
                    else -> onComposerEvent(event)
                }
            },
            onMediaSelected = onMediaSelected,
            onCameraClick = onCameraClick,
            onFileClick = onFileClick,
            onLocationClick = onLocationClick,
            onContactClick = onContactClick,
            // GIF Picker
            gifPickerState = gifPickerState,
            gifSearchQuery = gifSearchQuery,
            onGifSearchQueryChange = onGifSearchQueryChange,
            onGifSearch = onGifSearch,
            onGifSelected = onGifSelected,
            onSendButtonBoundsChanged = onSendButtonBoundsChanged
        )
        Log.d("PerfTrace", "After ChatComposer: +${System.currentTimeMillis() - t0}ms")
    }
}
