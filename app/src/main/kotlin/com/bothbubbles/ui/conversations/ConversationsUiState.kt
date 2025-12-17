package com.bothbubbles.ui.conversations

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.services.sync.UnifiedSyncProgress
import com.bothbubbles.ui.components.common.ConnectionBannerState
import com.bothbubbles.ui.components.common.SmsBannerState
import com.bothbubbles.ui.components.conversation.SwipeConfig
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable

@Stable
data class ConversationsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    // Pagination state
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val currentPage: Int = 0,
    // Unified sync progress (replaces individual sync/SMS/categorization fields)
    val unifiedSyncProgress: UnifiedSyncProgress? = null,
    // Connection state
    val isConnected: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectionBannerState: ConnectionBannerState = ConnectionBannerState.Dismissed,
    val smsBannerState: SmsBannerState = SmsBannerState.Disabled,
    // SMS capability state (for settings warning badge, independent of banner dismissal)
    val smsEnabled: Boolean = false,
    val isSmsFullyFunctional: Boolean = false,
    val conversations: StableList<ConversationUiModel> = emptyList<ConversationUiModel>().toStable(),
    val searchQuery: String = "",
    val error: String? = null,
    val swipeConfig: SwipeConfig = SwipeConfig(),
    val messageSearchResults: StableList<MessageSearchResult> = emptyList<MessageSearchResult>().toStable(),
    val useSimpleAppTitle: Boolean = false,
    val userProfileName: String? = null,
    val userProfileAvatarUri: String? = null,
    // Categorization enabled flag (for settings)
    val categorizationEnabled: Boolean = false,
    // Per-category enabled flags
    val transactionsEnabled: Boolean = true,
    val deliveriesEnabled: Boolean = true,
    val promotionsEnabled: Boolean = true,
    val remindersEnabled: Boolean = true,
    // Conversation filter state (persisted)
    val conversationFilter: String = "all",
    val categoryFilter: String? = null
) {
    /**
     * True when there's a problem in settings that needs attention.
     * Used to show a warning badge on the settings gear icon.
     * This is independent of banner dismissal state - warning persists until fixed.
     */
    val hasSettingsWarning: Boolean
        get() {
            // iMessage warning: not connected and not in "not configured" state (means connection issue)
            val hasIMessageWarning = connectionState == ConnectionState.ERROR ||
                    connectionState == ConnectionState.DISCONNECTED
            // SMS warning: enabled but not fully functional (regardless of banner dismissal)
            val hasSmsWarning = smsEnabled && !isSmsFullyFunctional
            return hasIMessageWarning || hasSmsWarning
        }

    /**
     * Calculate the unread count based on the current filter.
     * This is used for the notification badge and header display.
     */
    val filteredUnreadCount: Int
        get() {
            val filtered = conversations.filter { conv ->
                // Apply status filter first
                val matchesStatus = when (conversationFilter.lowercase()) {
                    "all" -> !conv.isSpam
                    "unread" -> !conv.isSpam && conv.unreadCount > 0
                    "spam" -> conv.isSpam
                    "unknown_senders" -> !conv.isSpam && !conv.hasContact
                    "known_senders" -> !conv.isSpam && conv.hasContact
                    else -> !conv.isSpam
                }

                // Apply category filter if set
                val matchesCategory = categoryFilter?.let { category ->
                    conv.category?.equals(category, ignoreCase = true) == true
                } ?: true

                matchesStatus && matchesCategory
            }
            return filtered.sumOf { it.unreadCount }
        }
}

/**
 * Represents a message that matched a search query.
 * Contains all data needed to render using GoogleStyleConversationTile.
 */
@Stable
data class MessageSearchResult(
    val messageGuid: String,
    val chatGuid: String,
    val chatDisplayName: String,
    val messageText: String,
    val timestamp: Long,
    val formattedTime: String,
    val isFromMe: Boolean,
    val avatarPath: String? = null,
    val isGroup: Boolean = false,
    val messageType: MessageType = MessageType.TEXT,
    val linkTitle: String? = null,
    val linkDomain: String? = null
) {
    /**
     * Converts this search result to a ConversationUiModel for rendering with GoogleStyleConversationTile.
     * Uses simplified defaults for fields not relevant to search result display.
     */
    fun toConversationUiModel(): ConversationUiModel = ConversationUiModel(
        guid = chatGuid,
        displayName = chatDisplayName,
        avatarPath = avatarPath,
        lastMessageText = messageText,
        lastMessageTime = formattedTime,
        lastMessageTimestamp = timestamp,
        unreadCount = 0,
        isPinned = false,
        isMuted = false,
        isGroup = isGroup,
        isTyping = false,
        isFromMe = isFromMe,
        hasDraft = false,
        draftText = null,
        lastMessageType = messageType,
        lastMessageStatus = MessageStatus.NONE,
        participantNames = emptyList(),
        address = "",
        hasInferredName = false,
        inferredName = null,
        lastMessageLinkTitle = linkTitle,
        lastMessageLinkDomain = linkDomain,
        isSpam = false,
        category = null
    )
}

@Stable
data class ConversationUiModel(
    val guid: String,
    val displayName: String,
    val avatarPath: String?,
    val chatAvatarPath: String? = null, // Custom group photo set by user (takes precedence over participant collage)
    val lastMessageText: String,
    val lastMessageTime: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val pinIndex: Int = Int.MAX_VALUE, // Order for pinned items (lower = earlier)
    val isMuted: Boolean,
    val isGroup: Boolean,
    val isTyping: Boolean,
    val isFromMe: Boolean = false,
    val hasDraft: Boolean = false,
    val draftText: String? = null,
    val lastMessageType: MessageType = MessageType.TEXT,
    val lastMessageStatus: MessageStatus = MessageStatus.NONE,
    val participantNames: List<String> = emptyList(),
    val participantAvatarPaths: List<String?> = emptyList(), // Avatar paths for group participants
    val address: String = "", // Primary address (phone/email) for the chat
    val hasInferredName: Boolean = false, // True if displaying an inferred "Maybe: X" name
    val inferredName: String? = null, // Raw inferred name without "Maybe:" prefix (for add contact)
    val lastMessageLinkTitle: String? = null, // Link preview title for LINK type messages
    val lastMessageLinkDomain: String? = null, // Link domain for LINK type messages
    val isSpam: Boolean = false, // Whether this conversation is marked as spam
    val category: String? = null, // Message category: "transactions", "deliveries", "promotions", "reminders"
    val isSnoozed: Boolean = false, // Whether notifications are snoozed
    val snoozeUntil: Long? = null, // When snooze expires (-1 = indefinite)
    val lastMessageSource: String? = null, // Message source: IMESSAGE, SERVER_SMS, LOCAL_SMS, LOCAL_MMS
    val lastMessageSenderName: String? = null, // Sender's first name for group chats (null if isFromMe or 1:1)
    // Merged conversation support (for combining iMessage and SMS threads to same contact)
    val mergedChatGuids: List<String> = listOf(), // All chat GUIDs in this merged conversation
    val isMerged: Boolean = false, // True if this represents multiple merged chats
    val contactKey: String = "", // Normalized phone/email for matching
    // Enhanced preview fields
    val isInvisibleInk: Boolean = false, // Whether message uses invisible ink effect
    val reactionPreviewData: ReactionPreviewData? = null, // Data for reaction preview formatting
    val groupEventText: String? = null, // Formatted group event text (e.g., "John left the group")
    val documentType: String? = null, // Document type name (e.g., "PDF", "Document")
    val attachmentCount: Int = 1 // Number of attachments for "2 Photos" style preview
) {
    /**
     * Returns true if this conversation has a saved contact.
     * A contact is considered "missing" if the displayName looks like a phone number or email address,
     * or if it's showing an inferred "Maybe:" name.
     */
    val hasContact: Boolean
        get() = !hasInferredName &&
                !displayName.contains("@") &&
                !displayName.matches(Regex("^[+\\d\\s()-]+$"))

    /**
     * Raw display name without "Maybe:" prefix - use for contact intents and avatars.
     */
    val rawDisplayName: String
        get() = if (hasInferredName && inferredName != null) inferredName else displayName
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE_MESSAGE,
    LINK,
    ATTACHMENT,
    STICKER,
    CONTACT,
    DOCUMENT,
    LOCATION,
    REACTION,
    GROUP_EVENT,
    DELETED,
    LIVE_PHOTO,
    APP_MESSAGE
}

/**
 * Data for reaction preview text generation
 */
@Immutable
data class ReactionPreviewData(
    val tapbackVerb: String,      // "Liked", "Loved", "Laughed at", etc.
    val originalText: String?,    // Truncated original message text
    val hasAttachment: Boolean    // If original message had an attachment
)

/**
 * Status of the last sent message for display in conversation list
 */
enum class MessageStatus {
    NONE,       // Not from me or no status available
    SENDING,    // Message is being sent (temp guid)
    SENT,       // Message sent but not delivered
    DELIVERED,  // Message delivered to recipient
    READ,       // Message read by recipient
    FAILED      // Message failed to send
}
