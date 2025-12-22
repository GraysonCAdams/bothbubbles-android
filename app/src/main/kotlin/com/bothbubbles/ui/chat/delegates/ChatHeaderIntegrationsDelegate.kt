package com.bothbubbles.ui.chat.delegates

import com.bothbubbles.ui.chat.integration.ChatHeaderContent
import com.bothbubbles.ui.chat.integration.ChatHeaderIntegration
import com.bothbubbles.ui.chat.state.ChatHeaderSubtextState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Delegate for managing chat header integrations (Life360, Calendar, etc.).
 *
 * This delegate:
 * - Collects content from all registered [ChatHeaderIntegration]s
 * - Combines and sorts content by priority
 * - Manages automatic cycling between multiple content items
 * - Triggers immediate cycle on content changes
 *
 * ## Cycling Behavior
 *
 * - Initial cycle after 5 seconds when chat opens
 * - Subsequent cycles every 30 seconds
 * - Immediate cycle when content changes (if triggerCycleOnChange is true)
 *
 * ## Usage
 *
 * Create via [Factory] in ChatViewModel:
 * ```kotlin
 * val headerIntegrations = headerIntegrationsFactory.create(
 *     participantAddresses = setOf("+1234567890"),
 *     isGroup = false,
 *     scope = viewModelScope
 * )
 * ```
 */
class ChatHeaderIntegrationsDelegate @AssistedInject constructor(
    private val integrations: Set<@JvmSuppressWildcards ChatHeaderIntegration>,
    @Assisted private val participantAddresses: Set<String>,
    @Assisted private val isGroup: Boolean,
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(
            participantAddresses: Set<String>,
            isGroup: Boolean,
            scope: CoroutineScope
        ): ChatHeaderIntegrationsDelegate
    }

    companion object {
        private const val TAG = "ChatHeaderIntegrations"
        private const val CYCLE_INTERVAL_MS = 30_000L  // 30 seconds
        private const val INITIAL_CYCLE_DELAY_MS = 5_000L  // 5 seconds after open
    }

    private val _state = MutableStateFlow(ChatHeaderSubtextState())
    val state: StateFlow<ChatHeaderSubtextState> = _state.asStateFlow()

    private var cycleJob: Job? = null
    private var previousContentTexts: Set<String> = emptySet()

    init {
        if (integrations.isEmpty()) {
            Timber.tag(TAG).d("No integrations registered")
        } else {
            Timber.tag(TAG).d("Starting with ${integrations.size} integrations: ${integrations.map { it.id }}")
            observeIntegrations()
            startCyclingTimer()
        }
    }

    /**
     * Manually advance to the next content item.
     */
    fun advanceCycle() {
        _state.update { current ->
            if (current.contentItems.size <= 1) return@update current
            val nextIndex = (current.currentIndex + 1) % current.contentItems.size
            Timber.tag(TAG).d("Cycling: ${current.currentIndex} -> $nextIndex")
            current.copy(currentIndex = nextIndex, isInitialPhase = false)
        }
    }

    /**
     * Update participant addresses (e.g., when chat info loads).
     */
    fun updateParticipants(addresses: Set<String>) {
        if (addresses == participantAddresses) return
        Timber.tag(TAG).d("Participants updated: $addresses")
        // Re-initialize with new addresses
        observeIntegrations()
    }

    private fun observeIntegrations() {
        if (participantAddresses.isEmpty()) {
            Timber.tag(TAG).d("No participants, skipping integration observation")
            return
        }

        // Collect content from all integrations
        val contentFlows = integrations.map { integration ->
            integration.observeContent(participantAddresses, isGroup)
                .distinctUntilChanged()
        }

        if (contentFlows.isEmpty()) return

        scope.launch {
            combine(contentFlows) { results: Array<ChatHeaderContent?> ->
                results.filterNotNull()
                    .sortedByDescending { it.priority }
            }.collect { newItems ->
                handleContentUpdate(newItems)
            }
        }
    }

    private fun handleContentUpdate(newItems: List<ChatHeaderContent>) {
        val newContentTexts = newItems.map { it.text }.toSet()
        val oldItems = _state.value.contentItems

        // Check if any new content with triggerCycleOnChange flag is actually new
        val shouldTriggerCycle = newItems.any { new ->
            new.triggerCycleOnChange &&
                !previousContentTexts.contains(new.text)
        }

        // Update state
        _state.update { current ->
            // Clamp index if items list shrunk
            val newIndex = if (newItems.size <= current.currentIndex) {
                0
            } else {
                current.currentIndex
            }
            current.copy(
                contentItems = newItems.toImmutableList(),
                currentIndex = newIndex
            )
        }

        previousContentTexts = newContentTexts

        // Trigger immediate cycle if new interesting content appeared
        if (shouldTriggerCycle && _state.value.hasMultipleItems) {
            Timber.tag(TAG).d("Content changed, triggering immediate cycle")
            advanceCycle()
        }

        Timber.tag(TAG).d("Content updated: ${newItems.size} items from ${newItems.map { it.sourceId }}")
    }

    private fun startCyclingTimer() {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            // Initial delay before first cycle
            delay(INITIAL_CYCLE_DELAY_MS)

            _state.update { it.copy(isInitialPhase = false) }

            if (_state.value.hasMultipleItems) {
                advanceCycle()
            }

            // Then cycle every 30 seconds
            while (isActive) {
                delay(CYCLE_INTERVAL_MS)
                if (_state.value.hasMultipleItems) {
                    advanceCycle()
                }
            }
        }
    }

    /**
     * Clean up resources when delegate is no longer needed.
     */
    fun dispose() {
        cycleJob?.cancel()
        cycleJob = null
    }
}
