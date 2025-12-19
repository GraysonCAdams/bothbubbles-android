package com.bothbubbles.ui.chat

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bothbubbles.ui.chat.components.InputMode
import com.bothbubbles.ui.chat.composer.ChatComposer
import com.bothbubbles.ui.chat.composer.ComposerEvent
import com.bothbubbles.ui.chat.composer.ComposerInputMode
import com.bothbubbles.ui.chat.composer.ComposerState
import com.bothbubbles.ui.chat.composer.RecordingState
import com.bothbubbles.ui.chat.composer.panels.GifItem
import com.bothbubbles.ui.components.input.SmartReplyChips
import com.bothbubbles.ui.components.input.SuggestionItem

/**
 * Extracted input area component containing:
 * - SmartReplyChips
 * - ChatComposer (which includes panels via ComposerPanelHost, and ReplyPreviewBar)
 *
 * Note: Attachment/Emoji/GIF pickers are rendered inside ChatComposer's ComposerPanelHost
 * to avoid component duplication.
 *
 * This extraction follows Stage 2 of the refactor plan to declutter ChatScreen.kt
 *
 * @param isBubbleMode When true, disables file picker and contact picker
 */
@Composable
fun ChatInputUI(
    // Composer delegate to collect state from (PERF: collected internally to avoid parent recomposition)
    composerDelegate: com.bothbubbles.ui.chat.delegates.ChatComposerDelegate,
    // Audio state for voice recording
    audioState: ChatAudioState,
    // Whether this is a local SMS chat (affects long-press behavior)
    isLocalSmsChat: Boolean,

    // Callbacks - Camera (passed through to ChatComposer)
    onCameraClick: () -> Unit,
    // Note: onEmojiSelected removed - emoji selection handled inside ComposerPanelHost
    // Callbacks - Smart reply
    onSmartReplyClick: (SuggestionItem) -> Unit,
    // Callbacks - Composer events
    onComposerEvent: (ComposerEvent) -> Unit,
    onMediaSelected: (List<android.net.Uri>) -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onContactClick: () -> Unit,
    onEtaClick: () -> Unit = {},
    isEtaSharingAvailable: Boolean = false,
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
    onSizeChanged: (Int) -> Unit = {},
    // Bubble mode - simplified UI for Android conversation bubbles
    isBubbleMode: Boolean = false
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    // PERF FIX: Collect composerState internally to avoid parent (ChatScreen) recomposition on every keystroke
    val composerState by composerDelegate.state.collectAsStateWithLifecycle()

    // PERF FIX: Collect state internally from delegates to avoid ChatScreen recomposition
    val smartReplySuggestions by composerDelegate.smartReplySuggestions.collectAsStateWithLifecycle()
    val gifPickerState by composerDelegate.gifPickerState.collectAsStateWithLifecycle()
    val gifSearchQuery by composerDelegate.gifSearchQuery.collectAsStateWithLifecycle()

    // Focus request state (e.g., after camera capture to show keyboard)
    val shouldRequestFocus by composerDelegate.requestTextFieldFocus.collectAsStateWithLifecycle()
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
            // Skip system bar insets in bubble mode - bubbles are floating windows without
            // their own system bars. Using these in bubbles causes incorrect padding.
            .then(if (!isBubbleMode) Modifier.navigationBarsPadding() else Modifier)
            .then(if (!isBubbleMode) Modifier.imePadding() else Modifier)
            .onSizeChanged { size -> onSizeChanged(size.height) }
    ) {
        // Note: Both attachment and emoji pickers are rendered inside ChatComposer's ComposerPanelHost
        // to avoid duplication. The showEmojiPicker state triggers activePanel = EmojiKeyboard
        // which is handled by ComposerPanelHost.EmojiKeyboardPanel

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

        ChatComposer(
            state = adjustedComposerState,
            onEvent = { event ->
                when (event) {
                    is ComposerEvent.OpenCamera -> onCameraClick()
                    is ComposerEvent.SendLongPress -> {
                        // Effect picker disabled in bubble mode and for SMS
                        if (!isLocalSmsChat && !isBubbleMode) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShowEffectPicker()
                        }
                    }
                    is ComposerEvent.OpenQualitySheet -> {
                        // Quality sheet disabled in bubble mode
                        if (!isBubbleMode) onShowQualitySheet()
                    }
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
            // File and contact pickers disabled in bubble mode
            onFileClick = if (isBubbleMode) ({ }) else onFileClick,
            onLocationClick = if (isBubbleMode) ({ }) else onLocationClick,
            onContactClick = if (isBubbleMode) ({ }) else onContactClick,
            // ETA sharing - disabled in bubble mode
            onEtaClick = if (isBubbleMode) ({ }) else onEtaClick,
            isEtaSharingAvailable = if (isBubbleMode) false else isEtaSharingAvailable,
            // GIF Picker - collected internally from delegate
            gifPickerState = gifPickerState,
            gifSearchQuery = gifSearchQuery,
            onGifSearchQueryChange = onGifSearchQueryChange,
            onGifSearch = onGifSearch,
            onGifSelected = onGifSelected,
            onSendButtonBoundsChanged = onSendButtonBoundsChanged,
            // Focus request (e.g., after camera capture to show keyboard)
            shouldRequestFocus = shouldRequestFocus,
            onFocusRequested = { composerDelegate.clearTextFieldFocusRequest() }
        )
    }
}
