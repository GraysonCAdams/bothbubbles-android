package com.bothbubbles.ui.chat.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.displayName
import com.bothbubbles.data.local.db.entity.rawDisplayName
import com.bothbubbles.ui.chat.VideoCallMethodDialog
import com.bothbubbles.ui.components.common.ConversationAvatar
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.dialogs.DiscordChannelHelpOverlay
import com.bothbubbles.ui.components.dialogs.DiscordChannelSetupDialog
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog
import com.bothbubbles.util.PhoneNumberFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailsScreen(
    chatGuid: String,
    onNavigateBack: () -> Unit,
    onSearchClick: () -> Unit = {},
    onMediaGalleryClick: (mediaType: String) -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onCreateGroupClick: (address: String, displayName: String, service: String, avatarPath: String?) -> Unit = { _, _, _, _ -> },
    onLife360MapClick: (participantAddress: String) -> Unit = {},
    viewModel: ConversationDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showVideoCallDialog by remember { mutableStateOf(false) }
    var showDiscordSetupDialog by remember { mutableStateOf(false) }
    var showDiscordHelpOverlay by remember { mutableStateOf(false) }
    var showLife360ActionsSheet by remember { mutableStateOf(false) }
    var selectedParticipant by remember { mutableStateOf<HandleEntity?>(null) }

    // Handle action states
    LaunchedEffect(actionState) {
        when (actionState) {
            ConversationDetailsViewModel.ActionState.Archived,
            ConversationDetailsViewModel.ActionState.Deleted,
            ConversationDetailsViewModel.ActionState.Blocked -> {
                onNavigateBack()
                viewModel.clearActionState()
            }
            ConversationDetailsViewModel.ActionState.Idle -> {}
        }
    }

    // Collapsing toolbar scroll behavior
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Calculate collapse progress (0 = fully expanded, 1 = fully collapsed)
    val collapseProgress = scrollBehavior.state.collapsedFraction

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    // Animated title that fades in when collapsed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(collapseProgress)
                    ) {
                        // Small avatar in collapsed state - use participant avatar for 1:1 chats
                        val avatarPath = if (uiState.chat?.isGroup == true) {
                            uiState.chat?.customAvatarPath
                        } else {
                            uiState.participants.firstOrNull()?.cachedAvatarPath
                        }
                        ConversationAvatar(
                            displayName = uiState.displayName,
                            isGroup = uiState.chat?.isGroup == true,
                            participantNames = uiState.participants.map { it.displayName },
                            participantAvatars = uiState.participants.map { it.cachedAvatarPath },
                            avatarPath = avatarPath,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = PhoneNumberFormatter.format(uiState.displayName),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Favorite toggle in TopAppBar (only for 1:1 chats with saved contacts)
                    if (uiState.chat?.isGroup != true && uiState.hasContact) {
                        IconButton(onClick = { viewModel.toggleStarred() }) {
                            Icon(
                                imageVector = if (uiState.isContactStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (uiState.isContactStarred) "Remove from favorites" else "Add to favorites",
                                tint = if (uiState.isContactStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Expanded header with large avatar (fades out when collapsing)
                item {
                    // Use participant avatar for 1:1 chats, custom avatar for groups
                    val headerAvatarPath = if (uiState.chat?.isGroup == true) {
                        uiState.chat?.customAvatarPath
                    } else {
                        uiState.participants.firstOrNull()?.cachedAvatarPath
                    }
                    CollapsingConversationHeader(
                        displayName = uiState.displayName,
                        subtitle = uiState.subtitle,
                        isGroup = uiState.chat?.isGroup == true,
                        participantNames = uiState.participants.map { it.displayName },
                        participantAvatars = uiState.participants.map { it.cachedAvatarPath },
                        avatarPath = headerAvatarPath,
                        collapseProgress = collapseProgress
                    )
                }

                // Action buttons row (4 buttons: Call, Video, Contact, Search)
                item {
                    val context = LocalContext.current
                    ActionButtonsRow(
                        hasContact = uiState.hasContact,
                        onCallClick = {
                            val phoneNumber = uiState.firstParticipantAddress
                            if (phoneNumber.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                                context.startActivity(intent)
                            }
                        },
                        onVideoClick = { showVideoCallDialog = true },
                        onContactInfoClick = {
                            viewContact(context, uiState.firstParticipantAddress)
                        },
                        onAddContactClick = {
                            launchAddContact(context, uiState.firstParticipantAddress, uiState.displayName)
                        },
                        onSearchClick = onSearchClick
                    )
                }

                // Life360 location section (only for 1:1 chats with linked Life360 member)
                val life360Member = uiState.life360Member
                if (uiState.chat?.isGroup != true && life360Member != null) {
                    item {
                        Life360LocationSection(
                            life360Member = life360Member,
                            onMapClick = {
                                // Navigate to full-screen map
                                onLife360MapClick(uiState.firstParticipantAddress)
                            }
                        )
                    }
                }

                // Media section
                item {
                    MediaSection(
                        imageCount = uiState.imageCount,
                        otherMediaCount = uiState.otherMediaCount,
                        recentImages = uiState.recentImages,
                        onImagesClick = { onMediaGalleryClick("images") },
                        onVideosLinksClick = { onMediaGalleryClick("all") }
                    )
                }

                // Chat options
                item {
                    ChatOptionsSection(
                        isPinned = uiState.isPinned,
                        isMuted = uiState.isMuted,
                        isSnoozed = uiState.isSnoozed,
                        snoozeUntil = uiState.snoozeUntil,
                        onSnoozeClick = { showSnoozeDialog = true },
                        onNotificationsClick = onNotificationSettingsClick,
                        onBlockReportClick = { showBlockDialog = true }
                    )
                }

                // Profile fields section (Discord channel for 1:1 chats)
                if (uiState.chat?.isGroup != true) {
                    item {
                        ProfileFieldsSection(
                            discordChannelId = uiState.discordChannelId,
                            isDiscordInstalled = viewModel.isDiscordInstalled(),
                            onDiscordEditClick = { showDiscordSetupDialog = true },
                            onDiscordClearClick = { viewModel.clearDiscordChannelId() }
                        )
                    }
                }

                // Participants section (for group chats or to show contact)
                item {
                    ParticipantsSection(
                        participants = uiState.participants,
                        isGroup = uiState.chat?.isGroup == true,
                        onCreateGroupClick = {
                            // Pass the first participant's info for pre-selection
                            val participant = uiState.participants.firstOrNull()
                            if (participant != null) {
                                onCreateGroupClick(
                                    participant.address,
                                    participant.displayName,
                                    participant.service,
                                    participant.cachedAvatarPath
                                )
                            }
                        },
                        onParticipantClick = { participant -> selectedParticipant = participant }
                    )
                }

                // Danger zone options
                item {
                    DangerZoneSection(
                        isArchived = uiState.isArchived,
                        onArchiveClick = { viewModel.toggleArchive() },
                        onDeleteClick = { showDeleteDialog = true }
                    )
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete conversation?") },
            text = { Text("This conversation will be deleted from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteChat()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Snooze duration dialog
    SnoozeDurationDialog(
        visible = showSnoozeDialog,
        currentSnoozeUntil = uiState.snoozeUntil,
        onDurationSelected = { duration ->
            viewModel.snoozeChat(duration.durationMs)
            showSnoozeDialog = false
        },
        onUnsnooze = {
            viewModel.unsnoozeChat()
            showSnoozeDialog = false
        },
        onDismiss = { showSnoozeDialog = false }
    )

    // Block confirmation dialog
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("Block contact?") },
            text = { Text("You won't receive messages or calls from this contact. They won't be notified.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBlockDialog = false
                        viewModel.blockContact()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Participant quick actions popup
    selectedParticipant?.let { participant ->
        ContactQuickActionsPopup(
            contactInfo = ContactInfo(
                chatGuid = "", // Not needed for participant popup
                displayName = participant.displayName,
                rawDisplayName = participant.rawDisplayName,
                avatarPath = participant.cachedAvatarPath,
                address = participant.address,
                isGroup = false,
                hasContact = participant.cachedDisplayName != null,
                hasInferredName = participant.hasInferredName
            ),
            onDismiss = { selectedParticipant = null },
            onMessageClick = {
                // Could navigate to 1:1 chat with this participant
                selectedParticipant = null
            },
            onContactAdded = {
                viewModel.refreshContactInfo(participant.address)
            }
        )
    }

    // Video call method dialog
    val context = LocalContext.current
    if (showVideoCallDialog) {
        VideoCallMethodDialog(
            onGoogleMeet = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"))
                context.startActivity(intent)
                showVideoCallDialog = false
            },
            onWhatsApp = {
                val phone = uiState.firstParticipantAddress.replace(Regex("[^0-9+]"), "")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
                context.startActivity(intent)
                showVideoCallDialog = false
            },
            onDiscord = {
                uiState.discordChannelId?.let { channelId ->
                    context.startActivity(viewModel.getDiscordCallIntent(channelId))
                }
                showVideoCallDialog = false
            },
            onDiscordSetup = {
                showVideoCallDialog = false
                showDiscordSetupDialog = true
            },
            onDismiss = { showVideoCallDialog = false },
            isWhatsAppAvailable = viewModel.isWhatsAppAvailable(),
            isDiscordAvailable = viewModel.isDiscordInstalled(),
            hasDiscordChannelId = uiState.discordChannelId != null
        )
    }

    // Discord channel setup dialog
    if (showDiscordSetupDialog) {
        DiscordChannelSetupDialog(
            currentChannelId = uiState.discordChannelId,
            contactName = uiState.displayName,
            onSave = { channelId ->
                viewModel.saveDiscordChannelId(channelId)
                showDiscordSetupDialog = false
            },
            onClear = {
                viewModel.clearDiscordChannelId()
                showDiscordSetupDialog = false
            },
            onDismiss = { showDiscordSetupDialog = false },
            onShowHelp = { showDiscordHelpOverlay = true }
        )
    }

    // Discord help overlay
    if (showDiscordHelpOverlay) {
        DiscordChannelHelpOverlay(
            onDismiss = { showDiscordHelpOverlay = false }
        )
    }

    // Life360 location actions sheet
    val life360MemberForSheet = uiState.life360Member
    if (showLife360ActionsSheet && life360MemberForSheet != null) {
        Life360LocationActionsSheet(
            life360Member = life360MemberForSheet,
            onDismiss = { showLife360ActionsSheet = false }
        )
    }
}
