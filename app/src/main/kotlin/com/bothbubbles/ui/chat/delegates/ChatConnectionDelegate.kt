package com.bothbubbles.ui.chat.delegates

import timber.log.Timber
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.messaging.ChatFallbackTracker
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.services.sync.CounterpartSyncService
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.TutorialState
import com.bothbubbles.ui.chat.state.ChatConnectionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
 * Uses AssistedInject to receive runtime parameters at construction time,
 * eliminating the need for a separate initialize() call.
 */
class ChatConnectionDelegate @AssistedInject constructor(
    private val chatFallbackTracker: ChatFallbackTracker,
    private val counterpartSyncService: CounterpartSyncService,
    private val chatRepository: ChatRepository,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
    @Assisted private val sendModeManager: ChatSendModeManager,
    @Assisted private val mergedChatGuids: List<String>
) {

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
            sendModeManager: ChatSendModeManager,
            mergedChatGuids: List<String>
        ): ChatConnectionDelegate
    }

    private val isMergedChat: Boolean = mergedChatGuids.size > 1

    // Counterpart sync state (independent of ChatSendModeManager)
    private val _counterpartSynced = MutableStateFlow(false)

    // Fallback state for UI observation
    data class FallbackState(
        val isInFallback: Boolean = false,
        val reason: FallbackReason? = null
    )
    private val _fallbackState = MutableStateFlow(FallbackState())
    val fallbackState: StateFlow<FallbackState> = _fallbackState.asStateFlow()

    // Combined state flow - initialized in init block
    val state: StateFlow<ChatConnectionState> = combine(
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
            _counterpartSynced,
            sendModeManager.serverFallbackBlocked
        ) { manuallySet, tutorial, counterpart, serverBlocked ->
            CombinedState2(manuallySet, tutorial, counterpart, serverBlocked)
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
            counterpartSynced = state2.counterpart,
            serverFallbackBlocked = state2.serverBlocked
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatConnectionState()
    )

    init {
        // Observe fallback mode
        observeFallbackMode()
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
        val counterpart: Boolean,
        val serverBlocked: Boolean
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
            Timber.d("Skipping counterpart check - already merged conversation")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Find unified chat ID for this chat
                val chat = chatRepository.getChat(chatGuid)
                val unifiedChatId = chat?.unifiedChatId
                if (unifiedChatId == null) {
                    Timber.d("No unified chat ID for $chatGuid - skipping counterpart check")
                    return@launch
                }

                val result = counterpartSyncService.checkAndRepairCounterpart(unifiedChatId)
                when (result) {
                    is CounterpartSyncService.CheckResult.Found -> {
                        Timber.i("Found and synced counterpart: ${result.chatGuid}")
                        // Emit event to refresh conversation (will include new chat's messages on next open)
                        setCounterpartSynced(true)
                    }
                    is CounterpartSyncService.CheckResult.NotFound -> {
                        Timber.d("No counterpart exists for this contact (likely Android user)")
                    }
                    is CounterpartSyncService.CheckResult.AlreadyVerified -> {
                        Timber.d("Already verified: hasCounterpart=${result.hasCounterpart}")
                    }
                    is CounterpartSyncService.CheckResult.Skipped -> {
                        Timber.d("Counterpart check skipped (group already complete)")
                    }
                    is CounterpartSyncService.CheckResult.Error -> {
                        Timber.w("Counterpart check failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error checking for counterpart")
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
        Timber.d("Counterpart synced: $synced for chat $chatGuid")
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
            showReveal: Boolean
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
