package com.bothbubbles.ui.chatcreator.delegates

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import com.bothbubbles.core.data.ConnectionState
import com.bothbubbles.core.network.api.BothBubblesApi
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.ui.chatcreator.ContactUiModel
import com.bothbubbles.ui.chatcreator.GroupChatUiModel
import com.bothbubbles.ui.chatcreator.GroupSetupNavigation
import com.bothbubbles.ui.chatcreator.SelectedRecipient
import com.bothbubbles.util.parsing.PhoneAndCodeParsingUtils
import javax.inject.Inject

/**
 * Delegate responsible for creating chats.
 *
 * This delegate handles:
 * - Creating direct chats (single recipient) by navigating to chat screen
 * - The actual iMessage chat creation happens when the first message is sent
 *   (BlueBubbles Private API requires a message to create a new chat)
 * - Preparing group chat setup navigation
 * - Selecting existing group chats
 * - Determining the correct service (iMessage vs SMS) based on availability check and activity history
 */
class ChatCreationDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val api: BothBubblesApi,
    private val socketConnection: SocketConnection,
    private val settingsDataStore: SettingsDataStore
) {

    companion object {
        /** Timeout for iMessage availability check */
        private const val AVAILABILITY_CHECK_TIMEOUT_MS = 3000L
    }

    /**
     * Result of a chat creation operation
     */
    sealed class ChatCreationResult {
        data class Success(val chatGuid: String) : ChatCreationResult()
        data class Error(val message: String) : ChatCreationResult()
        data class NavigateToGroupSetup(val navigation: GroupSetupNavigation) : ChatCreationResult()
    }

    private val _createdChatGuid = MutableStateFlow<String?>(null)
    val createdChatGuid: StateFlow<String?> = _createdChatGuid.asStateFlow()

    private val _navigateToGroupSetup = MutableStateFlow<GroupSetupNavigation?>(null)
    val navigateToGroupSetup: StateFlow<GroupSetupNavigation?> = _navigateToGroupSetup.asStateFlow()

    /**
     * Select a contact and create/navigate to a direct chat.
     * Determines the correct service using availability check and activity history.
     */
    suspend fun selectContact(contact: ContactUiModel): ChatCreationResult {
        Timber.d("selectContact: address=${contact.address}, displayService=${contact.service}")

        // Determine the actual service to use (may differ from display service)
        val service = determineServiceForAddress(contact.address)
        Timber.d("selectContact: determined service=$service")

        return startConversationWithAddress(contact.address, service)
    }

    /**
     * Determine the correct service (iMessage vs SMS) for an address.
     *
     * Priority hierarchy:
     * 1. Check iMessage availability via API (if connected, with 3s timeout)
     * 2. Fall back to most recent chat that HAS messages
     * 3. Default to SMS for phone numbers, iMessage for emails
     *
     * @param address The phone number or email address
     * @return "iMessage" or "SMS"
     */
    private suspend fun determineServiceForAddress(address: String): String {
        // Emails are always iMessage
        if (address.contains("@")) {
            return "iMessage"
        }

        // Normalize phone number
        val normalized = PhoneAndCodeParsingUtils.normalizePhoneNumber(address)

        // Check if we're in SMS-only mode
        val smsOnlyMode = settingsDataStore.smsOnlyMode.first()
        if (smsOnlyMode) {
            Timber.d("SMS-only mode enabled, using SMS")
            return "SMS"
        }

        // Step 1: Try API check if connected (with timeout)
        val isConnected = socketConnection.connectionState.value == ConnectionState.CONNECTED
        if (isConnected) {
            try {
                val available = withTimeout(AVAILABILITY_CHECK_TIMEOUT_MS) {
                    val response = api.checkIMessageAvailability(normalized)
                    response.isSuccessful && response.body()?.data?.available == true
                }
                if (available) {
                    Timber.d("iMessage availability check: AVAILABLE")
                    return "iMessage"
                } else {
                    Timber.d("iMessage availability check: NOT AVAILABLE")
                    return "SMS"
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("iMessage availability check timed out, falling back to activity-based logic")
            } catch (e: Exception) {
                Timber.w(e, "iMessage availability check failed, falling back to activity-based logic")
            }
        } else {
            Timber.d("Server not connected, using activity-based fallback")
        }

        // Step 2: Fall back to most recent chat that HAS messages
        val activityService = chatRepository.getServiceFromMostRecentActiveChat(normalized)
        if (activityService != null) {
            Timber.d("Using service from most recent active chat: $activityService")
            return activityService
        }

        // Step 3: No active chat - default to SMS for phone numbers
        Timber.d("No active chat found, defaulting to SMS")
        return "SMS"
    }

    /**
     * Start a conversation with a manually entered address (phone number or email).
     *
     * For both iMessage and SMS, we first look for existing chats with this address.
     * If no existing chat with messages is found, we create a local chat entry.
     * The actual chat creation on the server (for iMessage) happens when the first message is sent,
     * since BlueBubbles Private API requires a message to create a new chat.
     */
    suspend fun startConversationWithAddress(address: String, service: String): ChatCreationResult {
        Timber.d("startConversationWithAddress: service=$service")

        return try {
            // Normalize phone numbers, keep emails as-is
            val normalizedAddress = if (address.contains("@")) {
                address.lowercase()
            } else {
                PhoneAndCodeParsingUtils.normalizePhoneNumber(address)
            }

            // Determine if this is iMessage or local messaging (SMS/RCS/MMS)
            val isIMessage = service.equals("iMessage", ignoreCase = true)

            // Check ALL possible GUID variants for this address, regardless of requested service
            // A contact might have chats under different services (e.g., RCS chat but iMessage handle)
            // Server uses different prefixes: iMessage, SMS, RCS, MMS (case-sensitive)
            val possibleGuids = listOf(
                "iMessage;-;$normalizedAddress",
                "SMS;-;$normalizedAddress",
                "RCS;-;$normalizedAddress",
                "MMS;-;$normalizedAddress",
                "sms;-;$normalizedAddress"  // Lowercase fallback
            )

            Timber.d("Looking for existing chats with various service prefixes")

            // Find the best existing chat (prefer ones with messages)
            var bestChat: ChatEntity? = null
            for (guid in possibleGuids) {
                val chat = chatRepository.getChat(guid)
                if (chat != null) {
                    // Prefer chats that have messages (latestMessageDate is not null)
                    if (chat.latestMessageDate != null) {
                        Timber.d("Found existing chat with messages: ${chat.guid}")
                        _createdChatGuid.value = chat.guid
                        return ChatCreationResult.Success(chat.guid)
                    }
                    // Keep track of first empty chat as fallback
                    if (bestChat == null) {
                        bestChat = chat
                    }
                }
            }

            // If we found an empty chat, use it
            if (bestChat != null) {
                Timber.d("Using existing empty chat: ${bestChat.guid}")
                _createdChatGuid.value = bestChat.guid
                return ChatCreationResult.Success(bestChat.guid)
            }

            // No existing chat found - create a new one
            // Use uppercase prefix to match server convention
            val servicePrefix = if (isIMessage) "iMessage" else "SMS"
            val newGuid = "$servicePrefix;-;$normalizedAddress"

            Timber.d("Creating local chat entry: $newGuid")
            val newChat = ChatEntity(
                guid = newGuid,
                chatIdentifier = normalizedAddress,
                displayName = null,
                isGroup = false,
                latestMessageDate = System.currentTimeMillis()
            )
            chatRepository.insertChat(newChat)

            Timber.d("Chat created: $newGuid")
            _createdChatGuid.value = newGuid
            ChatCreationResult.Success(newGuid)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start conversation")
            ChatCreationResult.Error(e.message ?: "Failed to create chat")
        }
    }

    /**
     * Handle continue action with selected recipients
     */
    fun handleContinue(recipients: List<SelectedRecipient>): ChatCreationResult {
        Timber.d("handleContinue called with ${recipients.size} recipients: ${recipients.map { "${it.displayName} (${it.service})" }}")
        return when {
            recipients.isEmpty() -> {
                ChatCreationResult.Error("Please add at least one recipient")
            }
            recipients.size == 1 -> {
                // Single recipient - caller should create direct chat
                ChatCreationResult.Error("Use startConversationWithAddress for single recipient")
            }
            else -> {
                // Multiple recipients - navigate to group setup
                val participantsJson = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.bothbubbles.ui.chatcreator.GroupParticipant.serializer()
                    ),
                    recipients.map { recipient ->
                        com.bothbubbles.ui.chatcreator.GroupParticipant(
                            address = recipient.address,
                            displayName = recipient.displayName,
                            service = recipient.service,
                            avatarPath = recipient.avatarPath,
                            isManualEntry = recipient.isManualEntry
                        )
                    }
                )
                // Determine group service type
                val allIMessage = recipients.all { it.service.equals("iMessage", ignoreCase = true) }
                val groupService = if (allIMessage) "IMESSAGE" else "MMS"

                val navigation = GroupSetupNavigation(
                    participantsJson = participantsJson,
                    groupService = groupService
                )
                _navigateToGroupSetup.value = navigation
                ChatCreationResult.NavigateToGroupSetup(navigation)
            }
        }
    }

    /**
     * Select an existing group chat
     */
    fun selectGroupChat(groupChat: GroupChatUiModel) {
        _createdChatGuid.value = groupChat.guid
    }

    /**
     * Select a group chat by its GUID directly
     */
    fun selectGroupChatByGuid(chatGuid: String) {
        _createdChatGuid.value = chatGuid
    }

    /**
     * Reset the created chat GUID
     */
    fun resetCreatedChatGuid() {
        _createdChatGuid.value = null
    }

    /**
     * Reset the group setup navigation state
     */
    fun resetGroupSetupNavigation() {
        _navigateToGroupSetup.value = null
    }
}
