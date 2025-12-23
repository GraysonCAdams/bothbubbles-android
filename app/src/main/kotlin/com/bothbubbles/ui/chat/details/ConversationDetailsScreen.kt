package com.bothbubbles.ui.chat.details

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    var showCalendarPickerDialog by remember { mutableStateOf(false) }
    var showLife360ActionsSheet by remember { mutableStateOf(false) }
    var selectedParticipant by remember { mutableStateOf<HandleEntity?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Calendar permission state
    var hasCalendarPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PermissionChecker.PERMISSION_GRANTED
        )
    }

    // Calendar permission launcher
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPermission = granted
        if (granted) {
            // Permission granted, show the calendar picker
            showCalendarPickerDialog = true
        } else {
            // Check if we should show rationale - if false, user permanently denied
            val activity = context.findActivity()
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_CALENDAR)
            } ?: true

            if (!shouldShowRationale) {
                // Permission permanently denied - open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    // Re-check calendar permission when returning from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCalendarPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CALENDAR
                ) == PermissionChecker.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // Track scroll state to determine when header is scrolled out of view
    // Use rememberSaveable to persist scroll position across process death
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Calculate how much of the header is visible (0 = fully visible, 1 = scrolled away)
    val headerHeightPx = with(LocalDensity.current) { 180.dp.toPx() }
    val headerScrollProgress by remember {
        derivedStateOf {
            val firstVisibleItem = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset

            if (firstVisibleItem > 0) {
                // Header completely scrolled away
                1f
            } else {
                // Calculate progress based on scroll offset
                (firstVisibleOffset / headerHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Avatar + name fade in as header scrolls out of view
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(headerScrollProgress)
                    ) {
                        val avatarPath = if (uiState.chat?.isGroup == true) {
                            uiState.unifiedChat?.effectiveAvatarPath
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
                colors = TopAppBarDefaults.topAppBarColors(
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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large centered header (fades out as it scrolls, app bar title fades in)
                item {
                    val headerAvatarPath = if (uiState.chat?.isGroup == true) {
                        uiState.unifiedChat?.effectiveAvatarPath
                    } else {
                        uiState.participants.firstOrNull()?.cachedAvatarPath
                    }
                    // Fade out as we scroll
                    val headerAlpha = 1f - headerScrollProgress
                    ConversationHeader(
                        displayName = uiState.displayName,
                        subtitle = uiState.subtitle,
                        isGroup = uiState.chat?.isGroup == true,
                        participantNames = uiState.participants.map { it.displayName },
                        participantAvatars = uiState.participants.map { it.cachedAvatarPath },
                        avatarPath = headerAvatarPath,
                        modifier = Modifier.alpha(headerAlpha)
                    )
                }

                // Action buttons row (4 buttons: Call, Video, Contact, Search)
                item {
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

                // Life360 location section
                val life360Members = uiState.life360Members
                val isGroup = uiState.chat?.isGroup == true

                if (life360Members.isNotEmpty()) {
                    item {
                        val isRefreshingLife360 by viewModel.isRefreshingLife360.collectAsStateWithLifecycle()

                        if (isGroup || life360Members.size > 1) {
                            // Group chat or multiple members: show multi-member locations section
                            // Build member-to-avatar mapping
                            val membersWithAvatars = remember(life360Members, uiState.participants) {
                                life360Members.map { member ->
                                    // Find the participant matching this member's phone number
                                    val matchingParticipant = uiState.participants.find { participant ->
                                        participant.address == member.phoneNumber
                                    }
                                    Life360MemberWithAvatar(
                                        member = member,
                                        avatarPath = matchingParticipant?.cachedAvatarPath
                                    )
                                }
                            }

                            Life360LocationsSection(
                                members = membersWithAvatars,
                                isRefreshing = isRefreshingLife360,
                                onMapClick = {
                                    // Navigate to full-screen map showing all members
                                    // For group chats, pass the chat guid or first participant
                                    onLife360MapClick(uiState.firstParticipantAddress)
                                },
                                onMemberRefreshClick = viewModel::refreshLife360LocationFor
                            )
                        } else {
                            // 1:1 chat with single member: show original single-member section
                            val life360Member = life360Members.first()
                            Life360LocationSection(
                                life360Member = life360Member,
                                avatarPath = uiState.participants.firstOrNull()?.cachedAvatarPath,
                                isRefreshing = isRefreshingLife360,
                                onMapClick = {
                                    // Navigate to full-screen map
                                    onLife360MapClick(uiState.firstParticipantAddress)
                                },
                                onRefreshClick = viewModel::refreshLife360Location
                            )
                        }
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

                // Profile fields section (Discord channel + Calendar for 1:1 chats)
                if (uiState.chat?.isGroup != true) {
                    item {
                        ProfileFieldsSection(
                            discordChannelId = uiState.discordChannelId,
                            isDiscordInstalled = viewModel.isDiscordInstalled(),
                            calendarAssociation = uiState.calendarAssociation,
                            onDiscordEditClick = { showDiscordSetupDialog = true },
                            onDiscordClearClick = { viewModel.clearDiscordChannelId() },
                            onCalendarEditClick = {
                                if (hasCalendarPermission) {
                                    showCalendarPickerDialog = true
                                } else {
                                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }
                            },
                            onCalendarClearClick = { viewModel.clearCalendarAssociation() }
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

    // Calendar picker dialog
    if (showCalendarPickerDialog) {
        CalendarPickerDialog(
            currentCalendarId = uiState.calendarAssociation?.calendarId,
            contactName = uiState.displayName,
            onCalendarSelected = { calendar ->
                viewModel.setCalendarAssociation(calendar)
                showCalendarPickerDialog = false
            },
            onDismiss = { showCalendarPickerDialog = false },
            getCalendars = viewModel::getAvailableCalendars
        )
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
