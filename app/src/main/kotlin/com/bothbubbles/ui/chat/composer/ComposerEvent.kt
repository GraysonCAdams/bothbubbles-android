package com.bothbubbles.ui.chat.composer

import android.net.Uri
import com.bothbubbles.ui.chat.ChatSendMode

/**
 * Events emitted by the ChatComposer component.
 *
 * These events follow a unidirectional data flow pattern:
 * User Interaction → ComposerEvent → ViewModel → State Update → UI
 *
 * The ViewModel handles these events and updates the ComposerState accordingly.
 */
sealed interface ComposerEvent {

    // ============================================
    // Text Input Events
    // ============================================

    /**
     * User changed the text in the input field.
     */
    data class TextChanged(val text: String) : ComposerEvent

    /**
     * User focused or unfocused the text field.
     */
    data class TextFieldFocusChanged(val isFocused: Boolean) : ComposerEvent

    /**
     * User moved the cursor position.
     */
    data class CursorPositionChanged(val position: Int) : ComposerEvent

    // ============================================
    // Send Events
    // ============================================

    /**
     * User tapped the send button.
     */
    data object Send : ComposerEvent

    /**
     * User long-pressed the send button (opens effect picker for iMessage).
     */
    data object SendLongPress : ComposerEvent

    /**
     * User toggled the send mode (iMessage ↔ SMS).
     */
    data class ToggleSendMode(val newMode: ChatSendMode) : ComposerEvent

    /**
     * Send mode reveal animation completed.
     */
    data object RevealAnimationComplete : ComposerEvent

    // ============================================
    // Attachment Events
    // ============================================

    /**
     * User tapped the add/attach button.
     */
    data object ToggleMediaPicker : ComposerEvent

    /**
     * User added attachments.
     */
    data class AddAttachments(val uris: List<Uri>) : ComposerEvent

    /**
     * User removed a specific attachment.
     */
    data class RemoveAttachment(val attachment: AttachmentItem) : ComposerEvent

    /**
     * User cleared all attachments.
     */
    data object ClearAllAttachments : ComposerEvent

    /**
     * User tapped the quality indicator to change image quality.
     */
    data object OpenQualitySheet : ComposerEvent

    /**
     * User tapped edit on an attachment to edit it.
     */
    data class EditAttachment(val attachment: AttachmentItem) : ComposerEvent

    /**
     * User dismissed the attachment warning.
     */
    data object DismissAttachmentWarning : ComposerEvent

    /**
     * User chose to remove the attachment that caused the warning.
     */
    data object RemoveWarningAttachment : ComposerEvent

    /**
     * User reordered attachments via drag-and-drop.
     */
    data class ReorderAttachments(val attachments: List<AttachmentItem>) : ComposerEvent

    // ============================================
    // Reply Events
    // ============================================

    /**
     * User started replying to a message.
     */
    data class StartReply(val message: MessagePreview) : ComposerEvent

    /**
     * User dismissed the reply preview.
     */
    data object DismissReply : ComposerEvent

    // ============================================
    // Smart Reply Events
    // ============================================

    /**
     * User selected a smart reply chip.
     */
    data class SelectSmartReply(val reply: String) : ComposerEvent

    /**
     * Smart replies were dismissed (scrolled out of view or timeout).
     */
    data object DismissSmartReplies : ComposerEvent

    // ============================================
    // Panel Events
    // ============================================

    /**
     * User tapped the emoji button.
     */
    data object ToggleEmojiPicker : ComposerEvent

    /**
     * User tapped the GIF button.
     */
    data object ToggleGifPicker : ComposerEvent

    /**
     * User dismissed any open panel.
     */
    data object DismissPanel : ComposerEvent

    // ============================================
    // Camera Events
    // ============================================

    /**
     * User tapped the camera button.
     */
    data object OpenCamera : ComposerEvent

    /**
     * User tapped the image/gallery button.
     */
    data object OpenGallery : ComposerEvent

    // ============================================
    // Voice Recording Events
    // ============================================

    /**
     * User started voice recording (press-and-hold started).
     */
    data object StartVoiceRecording : ComposerEvent

    /**
     * User stopped voice recording (finger lifted).
     */
    data object StopVoiceRecording : ComposerEvent

    /**
     * User cancelled voice recording.
     */
    data object CancelVoiceRecording : ComposerEvent

    /**
     * User chose to restart recording.
     */
    data object RestartVoiceRecording : ComposerEvent

    /**
     * User toggled noise cancellation during recording.
     */
    data object ToggleNoiseCancellation : ComposerEvent

    /**
     * User toggled playback in preview mode.
     */
    data object TogglePreviewPlayback : ComposerEvent

    /**
     * User confirmed sending the voice memo.
     */
    data object SendVoiceMemo : ComposerEvent

    /**
     * User chose to re-record after preview.
     */
    data object ReRecordVoiceMemo : ComposerEvent

    /**
     * User tapped mic button (for permission or quick tap).
     */
    data object VoiceMemoTap : ComposerEvent

    // ============================================
    // Tutorial Events
    // ============================================

    /**
     * User completed a tutorial step.
     */
    data class TutorialStepComplete(val step: TutorialStep) : ComposerEvent

    /**
     * User skipped/dismissed the tutorial.
     */
    data object DismissTutorial : ComposerEvent

    // ============================================
    // SMS Events
    // ============================================

    /**
     * User tapped on the blocked SMS input (to prompt for default SMS app).
     */
    data object SmsInputBlockedTapped : ComposerEvent
}
