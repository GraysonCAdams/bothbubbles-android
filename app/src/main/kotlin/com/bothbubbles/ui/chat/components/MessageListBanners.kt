package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.delegates.ChatEtaSharingDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.delegates.ChatSyncDelegate
import com.bothbubbles.ui.chat.state.ChatInfoState

/**
 * Callbacks for message list banner actions.
 */
data class MessageListBannerCallbacks(
    val onSearchQueryChange: (query: String) -> Unit,
    val onCloseSearch: () -> Unit,
    val onNavigateSearchUp: () -> Unit,
    val onNavigateSearchDown: () -> Unit,
    val onViewAllSearchResults: () -> Unit,
    val onExitSmsFallback: () -> Unit,
    val onAddContact: () -> Unit,
    val onReportSpam: () -> Unit,
    val onDismissSaveContactBanner: () -> Unit,
    val onStartSharingEta: () -> Unit,
    val onDismissEtaBanner: () -> Unit
)

/**
 * Collection of banners displayed at the top of the message list.
 *
 * Includes:
 * - Inline search bar
 * - Sending indicator bar
 * - SMS fallback mode banner
 * - Save contact banner
 * - ETA sharing banner
 *
 * @param isBubbleMode When true, hides search, save contact, and ETA banners
 */
@Composable
fun MessageListBanners(
    searchDelegate: ChatSearchDelegate,
    sendDelegate: ChatSendDelegate,
    syncDelegate: ChatSyncDelegate,
    etaSharingDelegate: ChatEtaSharingDelegate,
    chatInfoState: ChatInfoState,
    callbacks: MessageListBannerCallbacks,
    modifier: Modifier = Modifier,
    isBubbleMode: Boolean = false
) {
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()
    val sendState by sendDelegate.state.collectAsStateWithLifecycle()
    val syncState by syncDelegate.state.collectAsStateWithLifecycle()
    val etaSharingState by etaSharingDelegate.etaSharingState.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        // Inline search bar (hidden in bubble mode)
        if (!isBubbleMode) {
            InlineSearchBar(
                visible = searchState.isActive,
                query = searchState.query,
                onQueryChange = callbacks.onSearchQueryChange,
                onClose = callbacks.onCloseSearch,
                onNavigateUp = callbacks.onNavigateSearchUp,
                onNavigateDown = callbacks.onNavigateSearchDown,
                currentMatch = if (searchState.matchIndices.isNotEmpty())
                    searchState.currentMatchIndex + 1 else 0,
                totalMatches = searchState.matchIndices.size,
                isSearchingDatabase = searchState.isSearchingDatabase,
                databaseResultCount = searchState.databaseResults.size,
                onViewAllClick = callbacks.onViewAllSearchResults
            )
        }

        // Sending indicator bar (kept in bubble mode - important feedback)
        SendingIndicatorBar(
            isVisible = sendState.isSending,
            isLocalSmsChat = chatInfoState.isLocalSmsChat || syncState.isInSmsFallbackMode,
            hasAttachments = sendState.pendingMessages.any { it.hasAttachments },
            progress = sendState.sendProgress,
            pendingMessages = sendState.pendingMessages
        )

        // SMS fallback mode banner (kept in bubble mode - important context)
        SendModeHelperText(
            visible = syncState.isInSmsFallbackMode && !chatInfoState.isLocalSmsChat,
            fallbackReason = syncState.fallbackReason,
            isServerConnected = syncState.isServerConnected,
            showExitAction = chatInfoState.isIMessageChat,
            onExitFallback = callbacks.onExitSmsFallback
        )

        // Save contact banner (hidden in bubble mode)
        if (!isBubbleMode) {
            SaveContactBanner(
                visible = chatInfoState.showSaveContactBanner,
                senderAddress = chatInfoState.unsavedSenderAddress ?: "",
                inferredName = chatInfoState.inferredSenderName,
                onAddContact = callbacks.onAddContact,
                onReportSpam = callbacks.onReportSpam,
                onDismiss = callbacks.onDismissSaveContactBanner
            )
        }

        // ETA sharing banner (hidden in bubble mode)
        if (!isBubbleMode) {
            EtaSharingBanner(
                etaState = etaSharingState,
                onStartSharing = callbacks.onStartSharingEta,
                onDismiss = callbacks.onDismissEtaBanner
            )
        }
    }
}
