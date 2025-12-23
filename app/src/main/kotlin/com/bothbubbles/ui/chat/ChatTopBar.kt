package com.bothbubbles.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.chat.components.CyclingSubtextDisplay
import com.bothbubbles.ui.chat.delegates.ChatHeaderIntegrationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatInfoDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendModeManager
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.GroupAvatar
import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Top app bar for the chat screen with internal state collection.
 * Displays chat title, avatar, and action buttons (video call, reels, overflow menu).
 *
 * PERF FIX: This signature collects state internally from delegates to avoid
 * ChatScreen recomposition when top bar state changes.
 *
 * @param operationsDelegate Delegate for operations state (internal collection)
 * @param chatInfoDelegate Delegate for chat info state (internal collection)
 * @param headerIntegrationsDelegate Delegate for cycling header content (Life360, Calendar, etc.)
 * @param sendModeManager Manager for send mode state (for menu visibility)
 * @param reelsFeedEnabled Whether the Reels feed feature is enabled
 * @param hasReelVideos Whether there are cached reel videos for this chat
 * @param unwatchedReelsCount Count of unwatched Reels received after initial sync (for badge)
 * @param isBubbleMode When true, shows simplified UI for Android conversation bubbles
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    // NEW: Delegates for internal collection
    operationsDelegate: ChatOperationsDelegate,
    chatInfoDelegate: ChatInfoDelegate,
    headerIntegrationsDelegate: ChatHeaderIntegrationsDelegate?,
    sendModeManager: ChatSendModeManager?,
    onBackClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onReelsClick: () -> Unit,
    onLife360MapClick: (participantAddress: String) -> Unit,
    onMenuAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    reelsFeedEnabled: Boolean = false,
    hasReelVideos: Boolean = false,
    unwatchedReelsCount: Int = 0,
    isBubbleMode: Boolean = false
) {
    // Collect state internally from delegates to avoid ChatScreen recomposition
    val operationsState by operationsDelegate.state.collectAsStateWithLifecycle()
    val infoState by chatInfoDelegate.state.collectAsStateWithLifecycle()
    val currentSendMode by sendModeManager?.currentSendMode?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ChatSendMode.IMESSAGE) }
    val showSendModeSwitch = sendModeManager?.canShowSendModeSwitch() ?: false

    // Get first participant address for Life360 map navigation
    val firstParticipantAddress = infoState.participantAddresses.firstOrNull() ?: ""

    ChatTopBarContent(
        chatTitle = infoState.chatTitle,
        avatarPath = infoState.avatarPath,
        groupPhotoPath = infoState.groupPhotoPath,
        isGroup = infoState.isGroup,
        participantNames = infoState.participantNames,
        participantAvatarPaths = infoState.participantAvatarPaths,
        isSnoozed = infoState.isSnoozed,
        isArchived = operationsState.isArchived,
        isStarred = operationsState.isStarred,
        showSubjectField = operationsState.showSubjectField,
        isLocalSmsChat = infoState.isLocalSmsChat,
        showSendModeSwitch = showSendModeSwitch,
        currentSendMode = currentSendMode,
        headerIntegrationsDelegate = headerIntegrationsDelegate,
        onLife360Click = { onLife360MapClick(firstParticipantAddress) },
        reelsFeedEnabled = reelsFeedEnabled,
        hasReelVideos = hasReelVideos,
        unwatchedReelsCount = unwatchedReelsCount,
        onBackClick = onBackClick,
        onDetailsClick = onDetailsClick,
        onVideoCallClick = onVideoCallClick,
        onReelsClick = onReelsClick,
        onMenuAction = onMenuAction,
        modifier = modifier,
        isBubbleMode = isBubbleMode
    )
}

/**
 * Internal implementation of ChatTopBar content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBarContent(
    chatTitle: String,
    avatarPath: String?,
    groupPhotoPath: String?,
    isGroup: Boolean,
    participantNames: List<String>,
    participantAvatarPaths: List<String?>,
    isSnoozed: Boolean,
    isArchived: Boolean,
    isStarred: Boolean,
    showSubjectField: Boolean,
    isLocalSmsChat: Boolean,
    showSendModeSwitch: Boolean,
    currentSendMode: ChatSendMode,
    headerIntegrationsDelegate: ChatHeaderIntegrationsDelegate?,
    onLife360Click: () -> Unit,
    reelsFeedEnabled: Boolean,
    hasReelVideos: Boolean,
    unwatchedReelsCount: Int,
    onBackClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onReelsClick: () -> Unit,
    onMenuAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    isBubbleMode: Boolean = false
) {
    var showOverflowMenu = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    // In bubble mode, show X (close) instead of back arrow
                    if (isBubbleMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (isBubbleMode) "Close" else "Back"
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // In bubble mode, title is not clickable (no details screen)
                modifier = if (isBubbleMode) Modifier else Modifier.clickable(onClick = onDetailsClick)
            ) {
                // Priority: groupPhotoPath (custom/server) > GroupAvatar (collage) > Avatar (contact)
                if (isGroup && groupPhotoPath != null) {
                    // Use custom or server group photo
                    Avatar(
                        name = chatTitle,
                        avatarPath = groupPhotoPath,
                        size = 40.dp
                    )
                } else if (isGroup && participantNames.size > 1) {
                    // Fall back to participant collage
                    GroupAvatar(
                        names = participantNames.ifEmpty { listOf(chatTitle) },
                        avatarPaths = participantAvatarPaths,
                        size = 40.dp
                    )
                } else {
                    // 1:1 chat - use contact photo
                    Avatar(
                        name = chatTitle,
                        avatarPath = avatarPath,
                        size = 40.dp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (PhoneNumberFormatter.isPhoneNumber(chatTitle)) {
                                PhoneNumberFormatter.format(chatTitle)
                            } else {
                                chatTitle
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isSnoozed) {
                            Icon(
                                Icons.Outlined.Snooze,
                                contentDescription = "Snoozed",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Cycling subtext (Life360 location, Calendar events, etc.)
                    // Only shown for 1:1 chats
                    if (!isGroup && headerIntegrationsDelegate != null) {
                        CyclingSubtextDisplay(
                            delegate = headerIntegrationsDelegate,
                            onLife360Click = onLife360Click
                        )
                    }
                }
            }
        },
        actions = {
            if (isBubbleMode) {
                // In bubble mode, show only expand button to open full app
                IconButton(onClick = onDetailsClick) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "Open in app")
                }
            } else {
                // Reels button - shown for all chats when enabled and has videos
                // Shows badge with unwatched count in bottom-right
                if (reelsFeedEnabled && hasReelVideos) {
                    BadgedBox(
                        badge = {
                            if (unwatchedReelsCount > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.semantics {
                                        contentDescription = "$unwatchedReelsCount unwatched reels"
                                    }
                                ) {
                                    Text(
                                        text = if (unwatchedReelsCount > 99) "99+" else unwatchedReelsCount.toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    ) {
                        IconButton(onClick = onReelsClick) {
                            Icon(Icons.Default.Subscriptions, contentDescription = "Reels feed")
                        }
                    }
                }

                // Video call button - only shown for 1:1 chats
                if (!isGroup) {
                    IconButton(onClick = onVideoCallClick) {
                        Icon(
                            Icons.Outlined.Videocam,
                            contentDescription = "Video call"
                        )
                    }
                }

                // Overflow menu button
                Box {
                    IconButton(onClick = { showOverflowMenu.value = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }

                    ChatOverflowMenu(
                        expanded = showOverflowMenu.value,
                        onDismissRequest = { showOverflowMenu.value = false },
                        menuState = ChatMenuState(
                            isGroupChat = isGroup,
                            isArchived = isArchived,
                            isStarred = isStarred,
                            showSubjectField = showSubjectField,
                            isSmsChat = isLocalSmsChat,
                            showSendModeSwitch = showSendModeSwitch,
                            currentSendMode = currentSendMode,
                            isBubbleMode = isBubbleMode
                        ),
                        onAction = { action ->
                            onMenuAction(action)
                            showOverflowMenu.value = false
                        }
                    )
                }
            }
        },
        modifier = modifier
    )
}
