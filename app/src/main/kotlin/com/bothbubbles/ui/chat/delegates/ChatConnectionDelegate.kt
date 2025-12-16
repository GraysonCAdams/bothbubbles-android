package com.bothbubbles.ui.chat.delegates

import android.util.Log
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.sync.CounterpartSyncService
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.chat.state.ChatConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for managing connection status, send modes, and availability.
 *
 * Handles:
 * - Connection state aggregation from ChatSendModeManager
 * - Send mode (iMessage/SMS) state exposure
 * - iMessage availability checking coordination
 * - Tutorial state management
 * - Counterpart sync status tracking
 *
 * This delegate wraps ChatSendModeManager to provide a unified [ChatConnectionState]
 * that can be observed by the UI. It also adds the counterpartSynced state which
 * tracks whether a missing iMessage/SMS counterpart was found and synced.
 *
 * Usage in ChatViewModel:
 * ```kotlin
 * class ChatViewModel @Inject constructor(
 *     val connection: ChatConnectionDelegate,
 *     ...
 * ) : ViewModel() {
 *     init {
 *         connection.initialize(chatGuid, viewModelScope, sendModeManager)
 *     }
 *
 *     // Access state directly: connection.state
 * }
 * ```
 */
class ChatConnectionDelegate @Inject constructor(
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository
) {
    companion object {
        private const val TAG = "ChatConnectionDelegate"
    }

    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope
    private lateinit var sendModeManager: ChatSendModeManager
    private var mergedChatGuids: List<String> = emptyList()
    private var isMergedChat: Boolean = false

    // Counterpart sync state (independent of ChatSendModeManager)
    private val _counterpartSynced = MutableStateFlow(false)

    // Fallback state for UI observation
    data class FallbackState(
        val isInFallback: Boolean = false,
        val reason: FallbackReason? = null
    )
    private val _fallbackState = MutableStateFlow(FallbackState())
    val fallbackState: StateFlow<FallbackState> = _fallbackState.asStateFlow()

    // Combined state flow
    private lateinit var _state: StateFlow<ChatConnectionState>
    val state: StateFlow<ChatConnectionState> get() = _state

    /**
     * Initialize the delegate with the chat context and send mode manager.
     * Must be called before accessing state.
     *
     * @param chatGuid The chat GUID
     * @param scope The CoroutineScope for launching coroutines
     * @param sendModeManager The ChatSendModeManager to wrap
     * @param mergedChatGuids List of merged chat GUIDs (for merged conversations)
     */
    fun initialize(
        chatGuid: String,
        scope: CoroutineScope,
        sendModeManager: ChatSendModeManager,
        mergedChatGuids: List<String> = listOf(chatGuid)
    ) {
        this.chatGuid = chatGuid
        this.scope = scope
        this.sendModeManager = sendModeManager
        this.mergedChatGuids = mergedChatGuids
        this.isMergedChat = mergedChatGuids.size > 1

        // Observe fallback mode
        observeFallbackMode()

        // Combine all send mode manager flows with counterpartSynced
        _state = combine(
            sendModeManager.currentSendMode,
            sendModeManager.contactIMessageAvailable,
            sendModeManager.isCheckingIMessageAvailability,
            sendModeManager.canToggleSendMode,
            sendModeManager.showSendModeRevealAnimation
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val sendMode = values[0] as? ChatSendMode ?: ChatSendMode.SMS
            val iMessageAvailable = values[1] as? Boolean
            val isChecking = values[2] as? Boolean ?: false
            val canToggle = values[3] as? Boolean ?: false
            val showReveal = values[4] as? Boolean ?: false
            CombinedState1(sendMode, iMessageAvailable, isChecking, canToggle, showReveal)
        }.combine(
            combine(
                sendModeManager.sendModeManuallySet,
                sendModeManager.tutorialState,
                _counterpartSynced
            ) { manuallySet, tutorial, counterpart ->
                CombinedState2(manuallySet, tutorial, counterpart)
            }
        ) { state1, state2 ->
            ChatConnectionState(
                currentSendMode = state1.sendMode,
                contactIMessageAvailable = state1.iMessageAvailable,
                isCheckingIMessageAvailability = state1.isChecking,
                canToggleSendMode = state1.canToggle,
                showSendModeRevealAnimation = state1.showReveal,
                sendModeManuallySet = state2.manuallySet,
                tutorialState = state2.tutorial,
                counterpartSynced = state2.counterpart
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ChatConnectionState()
        )
    }

    // Helper data classes for combine
    private data class CombinedState1(
        val sendMode: ChatSendMode,
        val iMessageAvailable: Boolean?,
        val isChecking: Boolean,
        val canToggle: Boolean,
        val showReveal: Boolean
    )

    private data class CombinedState2(
        val manuallySet: Boolean,
        val tutorial: TutorialState,
        val counterpart: Boolean
    )

    // ============================================================================
    // FALLBACK MODE & COUNTERPART SYNC
    // ============================================================================

    /**
     * Observe fallback mode from ChatFallbackTracker.
     * Updates [fallbackState] which can be observed by the ViewModel.
     */
    private fun observeFallbackMode() {
        scope.launch {
            chatFallbackTracker.fallbackStates.collect { fallbackStates ->
                val entry = fallbackStates[chatGuid]
                _fallbackState.value = FallbackState(
                    isInFallback = entry != null,
                    reason = entry?.reason
                )
            }
        }
    }

    /**
     * Tier 2 Lazy Repair: Check for missing counterpart chat (iMessage/SMS) and sync if found.
     *
     * When a unified group has only one chat (e.g., SMS only), this checks the server
     * for a counterpart (e.g., iMessage). If found, it syncs the counterpart to local DB
     * and links it to the unified group.
     *
     * This runs in the background and doesn't block the UI. Results are cached to avoid
     * repeated checks for contacts without counterparts (e.g., Android users).
     */
    fun checkAndRepairCounterpart() {
        // Only run for non-merged conversations (single chat in unified group)
        if (isMergedChat) {
            Log.d(TAG, "Skipping counterpart check - already merged conversation")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Find unified group for this chat
                val unifiedGroup = chatRepository.getUnifiedGroupForChat(chatGuid)
                if (unifiedGroup == null) {
                    Log.d(TAG, "No unified group for $chatGuid - skipping counterpart check")
                    return@launch
                }

                val result = counterpartSyncService.checkAndRepairCounterpart(unifiedGroup.id)
                when (result) {
                    is CounterpartSyncService.CheckResult.Found -> {
                        Log.i(TAG, "Found and synced counterpart: ${result.chatGuid}")
                        // Emit event to refresh conversation (will include new chat's messages on next open)
                        setCounterpartSynced(true)
                    }
                    is CounterpartSyncService.CheckResult.NotFound -> {
                        Log.d(TAG, "No counterpart exists for this contact (likely Android user)")
                    }
                    is CounterpartSyncService.CheckResult.AlreadyVerified -> {
                        Log.d(TAG, "Already verified: hasCounterpart=${result.hasCounterpart}")
                    }
                    is CounterpartSyncService.CheckResult.Skipped -> {
                        Log.d(TAG, "Counterpart check skipped (group already complete)")
                    }
                    is CounterpartSyncService.CheckResult.Error -> {
                        Log.w(TAG, "Counterpart check failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for counterpart", e)
            }
        }
    }

    /**
     * Mark that a counterpart chat was found and synced.
     * This is used by the lazy repair mechanism when a missing iMessage/SMS
     * counterpart is discovered and synced.
     */
    fun setCounterpartSynced(synced: Boolean) {
        _counterpartSynced.value = synced
        Log.d(TAG, "Counterpart synced: $synced for chat $chatGuid")
    }

    // ============================================================================
    // SEND MODE FORWARDING METHODS
    // These delegate to ChatSendModeManager for actual implementation
    // ============================================================================

    /**
     * Manually set send mode.
     * @param mode The target send mode
     * @param persist Whether to persist the choice to the database (default true)
     * @return true if the switch was successful, false otherwise
     */
    fun setSendMode(mode: ChatSendMode, persist: Boolean = true): Boolean {
        return sendModeManager.setSendMode(mode, persist)
    }

    /**
     * Try to toggle send mode.
     * @return true if the toggle was successful
     */
    fun tryToggleSendMode(): Boolean {
        return sendModeManager.tryToggleSendMode()
    }

    /**
     * Check if send mode toggle is currently available.
     */
    fun canToggleSendModeNow(): Boolean {
        return sendModeManager.canToggleSendModeNow()
    }

    /**
     * Mark the send mode reveal animation as shown.
     */
    fun markRevealAnimationShown() {
        sendModeManager.markRevealAnimationShown()
    }

    /**
     * Update tutorial state.
     */
    fun updateTutorialState(newState: TutorialState) {
        sendModeManager.updateTutorialState(newState)
    }

    /**
     * Handle successful toggle during tutorial.
     */
    fun onTutorialToggleSuccess() {
        sendModeManager.onTutorialToggleSuccess()
    }

    /**
     * Check iMessage availability for the current chat's contact.
     * Forwards to ChatSendModeManager with the callback for UI state updates.
     */
    fun checkIMessageAvailability(
        isGroup: Boolean,
        isIMessageChat: Boolean,
        isLocalSmsChat: Boolean,
        participantPhone: String?,
        onUpdateState: (
            contactAvailable: Boolean?,
            isChecking: Boolean,
            sendMode: ChatSendMode,
            canToggle: Boolean,
            showReveal: Boolean,
            smsBlocked: Boolean
        ) -> Unit
    ) {
        sendModeManager.checkIMessageAvailability(
            isGroup = isGroup,
            isIMessageChat = isIMessageChat,
            isLocalSmsChat = isLocalSmsChat,
            participantPhone = participantPhone,
            onUpdateState = onUpdateState
        )
    }

    /**
     * Check if iMessage is available and exit fallback mode if so.
     */
    fun checkAndMaybeExitFallback(participantPhone: String?) {
        sendModeManager.checkAndMaybeExitFallback(participantPhone)
    }
}
