package com.bothbubbles.ui.chat.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailsScreen(
    chatGuid: String,
    onNavigateBack: () -> Unit,
    onSearchClick: () -> Unit = {},
    onMediaGalleryClick: (mediaType: String) -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onCreateGroupClick: (address: String, displayName: String, service: String, avatarPath: String?) -> Unit = { _, _, _, _ -> },
    viewModel: ConversationDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
                // Header with avatar and name
                item {
                    ConversationHeader(
                        displayName = uiState.displayName,
                        subtitle = uiState.subtitle,
                        isGroup = uiState.chat?.isGroup == true,
                        participantNames = uiState.participants.map { it.displayName },
                        avatarPath = uiState.chat?.customAvatarPath
                    )
                }

                // Action buttons row
                item {
                    val context = LocalContext.current
                    ActionButtonsRow(
                        hasContact = uiState.hasContact,
                        isStarred = uiState.isContactStarred,
                        isGroup = uiState.chat?.isGroup == true,
                        onCallClick = {
                            val phoneNumber = uiState.firstParticipantAddress
                            if (phoneNumber.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                                context.startActivity(intent)
                            }
                        },
                        onVideoClick = {
                            val phoneNumber = uiState.firstParticipantAddress
                            if (phoneNumber.isNotBlank()) {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                                context.startActivity(Intent.createChooser(intent, "Video call with..."))
                            }
                        },
                        onContactInfoClick = {
                            viewContact(context, uiState.firstParticipantAddress)
                        },
                        onAddContactClick = {
                            launchAddContact(context, uiState.firstParticipantAddress, uiState.displayName)
                        },
                        onStarClick = { viewModel.toggleStarred() },
                        onSearchClick = onSearchClick
                    )
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
}
