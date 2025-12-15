package com.bothbubbles.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.data.local.db.entity.PendingSyncStatus
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.error.AppError

/**
 * Main UI state for the chat screen.
 *
 * NOTE: This state has been refactored. Domain-specific state is now managed by delegates:
 * - SendState (ChatSendDelegate): isSending, sendProgress, pendingMessages, queuedMessages,
 *   replyingToGuid, isForwarding, forwardSuccess, sendError
 * - SearchState (ChatSearchDelegate): isActive, query, matchIndices, currentMatchIndex,
 *   isSearchingDatabase, databaseResults, showResultsSheet
 * - OperationsState (ChatOperationsDelegate): isArchived, isStarred, chatDeleted, showSubjectField,
 *   isSpam, isReportedToCarrier, operationError
 * - SyncState (ChatSyncDelegate): isTyping, isServerConnected, isSyncing, isInSmsFallbackMode,
 *   fallbackReason, lastSyncTime
 * - EffectsState (ChatEffectsDelegate): activeScreenEffect, autoPlayEffects, replayOnScroll, reduceMotion
 * - ThreadState (ChatThreadDelegate): threadOverlay
 *
 * ChatUiState now contains only shared/chat-identity fields that don't fit cleanly into a delegate.
 */
@Stable
data class ChatUiState(
    // Chat metadata
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val avatarPath: String? = null,
    val participantNames: StableList<String> = emptyList<String>().toStable(),
    val participantAvatarPaths: StableList<String?> = emptyList<String?>().toStable(),
    // Loading state
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isSyncingMessages: Boolean = false,
    val canLoadMore: Boolean = true,
    // Messages (now in separate messagesState flow)
    val messages: StableList<MessageUiModel> = emptyList<MessageUiModel>().toStable(),
    // Error handling
    val error: String? = null,
    val appError: AppError? = null, // Structured error for UI display with retry support
    // Chat type
    val isLocalSmsChat: Boolean = false,
    val smsInputBlocked: Boolean = false, // True if SMS chat but not default SMS app
    val isIMessageChat: Boolean = false,
    // Attachments
    val attachmentCount: Int = 0,
    // Participant info (for contacts/block actions)
    val participantPhone: String? = null,
    // Unsaved sender banner
    val showSaveContactBanner: Boolean = false,
    val unsavedSenderAddress: String? = null,
    val inferredSenderName: String? = null, // Inferred name from self-introduction (for add contact pre-fill)
    // Highlighted message (from notification deep-link or search jump)
    val highlightedMessageGuid: String? = null,
    // Attachment Quality
    val attachmentQuality: AttachmentQuality = AttachmentQuality.STANDARD,
    val rememberQuality: Boolean = false,
    // Snooze
    val isSnoozed: Boolean = false,
    val snoozeUntil: Long? = null,
    // Reply state (derived from sendState.replyingToGuid)
    val replyToMessage: MessageUiModel? = null, // Message being replied to (for composer preview)
    // iMessage availability for send mode
    val currentSendMode: ChatSendMode = ChatSendMode.SMS, // Default to SMS until availability confirmed
    val contactIMessageAvailable: Boolean? = null, // null = unknown/checking, true = iMessage available
    val isCheckingIMessageAvailability: Boolean = false, // True while checking server
    // Attachment size warnings
    val attachmentWarning: AttachmentWarning? = null, // Warning/error for pending attachments
    // SMS/iMessage toggle feature
    val canToggleSendMode: Boolean = false, // True when both SMS and iMessage are available
    val showSendModeRevealAnimation: Boolean = false, // Show Pepsi-like dual-color animation on chat open
    val sendModeManuallySet: Boolean = false, // True if user has manually toggled the mode
    val tutorialState: TutorialState = TutorialState.NOT_SHOWN, // Tutorial state for first-time users
    // Sync integrity
    val counterpartSynced: Boolean = false, // True if counterpart chat was found and synced (refresh recommended)
    // ETA sharing (navigation)
    val isEtaSharingEnabled: Boolean = false,
    val isNavigationActive: Boolean = false,
    val isEtaSharing: Boolean = false,
    val currentEtaMinutes: Int = 0,
    val isEtaBannerDismissed: Boolean = false
)

/**
 * Tracks a pending outgoing message for progress bar display.
 */
@Immutable
data class PendingMessage(
    val tempGuid: String,
    val progress: Float,  // 0.0 to 1.0 (individual message progress)
    val hasAttachments: Boolean,
    val isLocalSms: Boolean  // Track protocol for color coding
)

/**
 * UI model for queued messages (persisted pending messages from the offline queue).
 */
@Stable
data class QueuedMessageUiModel(
    val localId: String,
    val text: String?,
    val hasAttachments: Boolean,
    val syncStatus: PendingSyncStatus,
    val errorMessage: String?,
    val createdAt: Long
)

/**
 * Warning or error for pending attachments.
 */
@Immutable
data class AttachmentWarning(
    val message: String,
    val isError: Boolean, // true = cannot send, false = can send with warning
    val suggestCompression: Boolean = false,
    val affectedUri: Uri? = null // Which attachment caused the issue (for removal)
)

/**
 * Current send mode for the chat.
 * Defaults to SMS for safety, switches to IMESSAGE when availability is confirmed
 * and server has been stable for 30+ seconds.
 */
enum class ChatSendMode {
    SMS,      // Send via SMS (default/fallback)
    IMESSAGE  // Send via iMessage through BlueBubbles server
}

// TutorialState is defined in SendModeToggleState.kt
