package com.bothbubbles.ui.chat.composer

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.components.message.MessageUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Unified composer state with clear separation of concerns.
 * This data class manages all UI state for the chat composer component.
 */
@Immutable
data class ComposerState(
    // Text Input
    val text: String = "",
    val cursorPosition: Int = 0,
    val isTextFieldFocused: Boolean = false,

    // Attachments
    val attachments: List<AttachmentItem> = emptyList(),
    val attachmentWarning: ComposerAttachmentWarning? = null,

    // Reply
    val replyToMessage: MessagePreview? = null,

    // Smart Replies
    val smartReplies: List<String> = emptyList(),
    val showSmartReplies: Boolean = false,

    // Send Mode
    val sendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    val canToggleSendMode: Boolean = false,
    val isSendModeAnimating: Boolean = false,

    // Panels
    val activePanel: ComposerPanel = ComposerPanel.None,

    // Input Mode
    val inputMode: ComposerInputMode = ComposerInputMode.TEXT,

    // Voice Recording
    val recordingState: RecordingState? = null,

    // Tutorial
    val tutorialState: ComposerTutorialState = ComposerTutorialState.Hidden,

    // Sending
    val isSending: Boolean = false,
    val sendProgress: Float? = null,


    // SMS-specific state
    val smsInputBlocked: Boolean = false,
    val isLocalSmsChat: Boolean = false,

    // Image quality selection
    val currentImageQuality: AttachmentQuality = AttachmentQuality.DEFAULT,
    val showQualitySheet: Boolean = false,

    // Mentions (group chats only)
    val mentions: ImmutableList<MentionSpan> = persistentListOf(),
    val mentionPopupState: MentionPopupState = MentionPopupState.Hidden,
    val isGroupChat: Boolean = false
) {
    /**
     * Whether the composer has content that can be sent.
     */
    val canSend: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty()

    /**
     * Whether any picker panel is expanded (for add button X animation).
     * Includes MediaPicker and GifPicker - tapping X should close either.
     */
    val isPickerExpanded: Boolean
        get() = activePanel == ComposerPanel.MediaPicker || activePanel == ComposerPanel.GifPicker

    /**
     * Whether the emoji keyboard is active (for emoji button highlighting).
     */
    val isEmojiPickerActive: Boolean
        get() = activePanel == ComposerPanel.EmojiKeyboard

    /**
     * Whether to show the voice memo button instead of send button.
     */
    val showVoiceButton: Boolean
        get() = text.isBlank() && attachments.isEmpty() && inputMode == ComposerInputMode.TEXT

    /**
     * Whether the current message will be sent as MMS (local SMS with attachments or long text).
     */
    val isMmsMode: Boolean
        get() = isLocalSmsChat && (attachments.isNotEmpty() || text.length > 160)

    /**
     * Whether the send mode is SMS.
     */
    val isSmsMode: Boolean
        get() = sendMode == ChatSendMode.SMS

    /**
     * Number of attachments currently staged.
     */
    val attachmentCount: Int
        get() = attachments.size

    /**
     * Whether there are image attachments that can have quality adjusted.
     */
    val hasCompressibleImages: Boolean
        get() = attachments.any { it.isImage }

    /**
     * Number of image attachments.
     */
    val imageAttachmentCount: Int
        get() = attachments.count { it.isImage }
}

/**
 * Panel types that can be displayed below the composer.
 */
enum class ComposerPanel {
    /** No panel shown, keyboard may be visible */
    None,

    /** Media picker grid (Gallery, Camera, GIFs, Files, etc.) */
    MediaPicker,

    /** Emoji keyboard replacement */
    EmojiKeyboard,

    /** GIF search and selection (Giphy/Tenor) */
    GifPicker
}

/**
 * Input mode for the chat composer.
 */
enum class ComposerInputMode {
    /** Standard text input mode */
    TEXT,

    /** Voice memo recording in progress */
    VOICE_RECORDING,

    /** Voice memo preview/playback before sending */
    VOICE_PREVIEW
}

/**
 * Represents an attachment item in the composer.
 */
@Stable
data class AttachmentItem(
    /** Unique identifier for the attachment */
    val id: String,

    /** Content URI of the attachment */
    val uri: Uri,

    /** MIME type of the attachment */
    val mimeType: String?,

    /** Display name of the attachment */
    val displayName: String?,

    /** File size in bytes */
    val sizeBytes: Long?,

    /** Whether this attachment is uploading */
    val isUploading: Boolean = false,

    /** Upload progress (0.0 to 1.0) */
    val uploadProgress: Float? = null,

    /** Error message if upload failed */
    val error: String? = null,

    /** Image quality for compression (images only) */
    val quality: AttachmentQuality = AttachmentQuality.DEFAULT,

    /** Caption text for this attachment */
    val caption: String? = null
) {
    /** Whether this is an image attachment */
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true

    /** Whether this is a video attachment */
    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true

    /** Whether this is an audio attachment */
    val isAudio: Boolean
        get() = mimeType?.startsWith("audio/") == true

    /** Whether this attachment has an error */
    val hasError: Boolean
        get() = error != null
}

/**
 * Warning about attachment size or compatibility.
 */
@Immutable
data class ComposerAttachmentWarning(
    /** Warning message to display */
    val message: String,

    /** Whether this is an error (blocks sending) vs. a warning (can still send) */
    val isError: Boolean,

    /** Whether compression is suggested to resolve the issue */
    val suggestCompression: Boolean = false,

    /** The specific attachment that caused the warning */
    val affectedUri: Uri? = null
)

/**
 * Lightweight preview of a message for reply context.
 */
@Immutable
data class MessagePreview(
    /** GUID of the message being replied to */
    val guid: String,

    /** Whether this message is from the current user */
    val isFromMe: Boolean,

    /** Display name of the sender (null if from me) */
    val senderName: String?,

    /** Text content preview */
    val text: String?,

    /** Whether the message has attachments */
    val hasAttachments: Boolean = false
) {
    companion object {
        /**
         * Create a MessagePreview from a MessageUiModel.
         */
        fun fromMessageUiModel(message: MessageUiModel): MessagePreview {
            return MessagePreview(
                guid = message.guid,
                isFromMe = message.isFromMe,
                senderName = message.senderName,
                text = message.text,
                hasAttachments = message.attachments.isNotEmpty()
            )
        }
    }
}

/**
 * State for voice recording.
 */
@Immutable
data class RecordingState(
    /** Duration of recording in milliseconds */
    val durationMs: Long = 0,

    /** Amplitude history for waveform visualization (normalized 0-1) */
    val amplitudeHistory: List<Float> = emptyList(),

    /** Whether noise cancellation is enabled */
    val isNoiseCancellationEnabled: Boolean = true,

    /** Playback position for preview mode (ms) */
    val playbackPositionMs: Long = 0,

    /** Whether preview is currently playing */
    val isPlaying: Boolean = false,

    /** URI of the recorded file (set when recording stops) */
    val recordedUri: Uri? = null
)

/**
 * State for the send mode toggle tutorial.
 */
sealed class ComposerTutorialState {
    /** Tutorial is not visible */
    data object Hidden : ComposerTutorialState()

    /** Tutorial is active, showing a specific step */
    data class Active(val step: TutorialStep) : ComposerTutorialState()

    /** Tutorial is completing with celebration animation */
    data object Completing : ComposerTutorialState()

    /** Check if tutorial is currently showing */
    val isVisible: Boolean
        get() = this !is Hidden
}

/**
 * Individual steps in the send mode tutorial.
 */
enum class TutorialStep(
    val title: String,
    val description: String,
    val gestureDirection: GestureDirection
) {
    INTRO(
        title = "Switch between iMessage and SMS",
        description = "Swipe up on the send button to switch to SMS",
        gestureDirection = GestureDirection.UP
    ),
    CONFIRM(
        title = "Now sending as SMS",
        description = "Swipe down to go back to iMessage",
        gestureDirection = GestureDirection.DOWN
    ),
    COMPLETE(
        title = "You're all set!",
        description = "The button color shows your current mode",
        gestureDirection = GestureDirection.NONE
    )
}

/**
 * Direction for gesture hints in tutorials.
 */
enum class GestureDirection {
    UP,
    DOWN,
    NONE
}

/**
 * Animation phase for the send button's visual state.
 * Mirrors SendButtonAnimationPhase from the existing code for compatibility.
 */
enum class SendButtonPhase {
    /** Resting state, showing solid current mode color */
    IDLE,

    /** Initial dual-color split animation showing both options */
    LOADING_REVEAL,

    /** Current mode color "filling" from bottom after reveal */
    SETTLING,

    /** User is actively dragging - colors roll with finger movement */
    DRAGGING,

    /** Animating to final position after threshold crossed */
    SNAPPING,

    /** Tutorial overlay is active (first time only) */
    TUTORIAL
}
