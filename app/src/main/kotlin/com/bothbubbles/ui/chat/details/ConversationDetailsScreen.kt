package com.bothbubbles.ui.chat.details

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bothbubbles.data.local.db.entity.AttachmentEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.dialogs.ContactInfo
import com.bothbubbles.ui.components.dialogs.ContactQuickActionsPopup
import com.bothbubbles.ui.components.common.ConversationAvatar
import com.bothbubbles.ui.components.common.SnoozeDuration
import com.bothbubbles.ui.components.dialogs.SnoozeDurationDialog
import com.bothbubbles.util.PhoneNumberFormatter
import java.io.File

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

@Composable
private fun ConversationHeader(
    displayName: String,
    subtitle: String,
    isGroup: Boolean,
    participantNames: List<String>,
    avatarPath: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        ConversationAvatar(
            displayName = displayName,
            isGroup = isGroup,
            participantNames = participantNames,
            avatarPath = avatarPath,
            size = 96.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = PhoneNumberFormatter.format(displayName),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        // Show subtitle only if it's different from the display name (after formatting)
        val formattedSubtitle = PhoneNumberFormatter.format(subtitle)
        val formattedDisplayName = PhoneNumberFormatter.format(displayName)
        if (subtitle.isNotBlank() && formattedSubtitle != formattedDisplayName) {
            Text(
                text = formattedSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionButtonsRow(
    hasContact: Boolean,
    isStarred: Boolean,
    isGroup: Boolean,
    onCallClick: () -> Unit,
    onVideoClick: () -> Unit,
    onContactInfoClick: () -> Unit,
    onAddContactClick: () -> Unit,
    onStarClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ActionButton(
            icon = Icons.Outlined.Phone,
            label = "Call",
            onClick = onCallClick
        )
        ActionButton(
            icon = Icons.Outlined.Videocam,
            label = "Video",
            onClick = onVideoClick
        )
        ActionButton(
            icon = if (hasContact) Icons.Outlined.Person else Icons.Outlined.PersonAdd,
            label = if (hasContact) "Contact info" else "Add contact",
            onClick = if (hasContact) onContactInfoClick else onAddContactClick
        )
        // Favorite button - only show for non-group chats with saved contacts
        if (!isGroup && hasContact) {
            ActionButton(
                icon = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (isStarred) "Favorited" else "Favorite",
                onClick = onStarClick
            )
        }
        ActionButton(
            icon = Icons.Outlined.Search,
            label = "Search",
            onClick = onSearchClick
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MediaSection(
    imageCount: Int,
    otherMediaCount: Int,
    recentImages: List<AttachmentEntity>,
    onImagesClick: () -> Unit,
    onVideosLinksClick: () -> Unit
) {
    val formattedImageCount = when {
        imageCount > 99 -> "99+"
        imageCount > 0 -> imageCount.toString()
        else -> ""
    }
    val formattedOtherCount = when {
        otherMediaCount > 99 -> "99+"
        otherMediaCount > 0 -> otherMediaCount.toString()
        else -> ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Images row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onImagesClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Images",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (formattedImageCount.isNotEmpty()) {
                    Text(
                        text = formattedImageCount,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Image thumbnails - show actual images or placeholder
            if (recentImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentImages) { attachment ->
                        Surface(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onImagesClick),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            if (attachment.localPath != null) {
                                AsyncImage(
                                    model = File(attachment.localPath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Videos, links, & more row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onVideosLinksClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Videos, links, & more",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (formattedOtherCount.isNotEmpty()) {
                    Text(
                        text = formattedOtherCount,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChatOptionsSection(
    isPinned: Boolean,
    isMuted: Boolean,
    isSnoozed: Boolean,
    snoozeUntil: Long?,
    onSnoozeClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onBlockReportClick: () -> Unit
) {
    val snoozeLabel = if (isSnoozed && snoozeUntil != null) {
        "Snoozed ${SnoozeDuration.formatRemainingTime(snoozeUntil)}"
    } else {
        "Snooze chat"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            OptionRow(
                icon = if (isSnoozed) Icons.Filled.Snooze else Icons.Outlined.Snooze,
                label = snoozeLabel,
                onClick = onSnoozeClick,
                tint = if (isSnoozed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = if (isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                label = if (isMuted) "Unmute notifications" else "Notifications",
                onClick = onNotificationsClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = Icons.Outlined.Block,
                label = "Block & report spam",
                onClick = onBlockReportClick,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ParticipantsSection(
    participants: List<HandleEntity>,
    isGroup: Boolean,
    onCreateGroupClick: () -> Unit,
    onParticipantClick: (HandleEntity) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isGroup) "${participants.size} people" else "1 other person",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCreateGroupClick) {
                    Icon(
                        imageVector = Icons.Outlined.GroupAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Create group")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Participant list
            participants.forEach { participant ->
                ParticipantRow(
                    participant = participant,
                    onClick = { onParticipantClick(participant) }
                )
            }
        }
    }
}

@Composable
private fun ParticipantRow(
    participant: HandleEntity,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            name = participant.displayName,
            avatarPath = participant.cachedAvatarPath,
            size = 48.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = PhoneNumberFormatter.format(participant.address),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DangerZoneSection(
    isArchived: Boolean,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            OptionRow(
                icon = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                label = if (isArchived) "Unarchive" else "Archive",
                onClick = onArchiveClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            OptionRow(
                icon = Icons.Outlined.Delete,
                label = "Delete conversation",
                onClick = onDeleteClick,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Launch the add contact screen with pre-filled info
 */
private fun launchAddContact(context: Context, address: String, name: String) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE

        // Check if address looks like a phone number or email
        if (address.contains("@")) {
            putExtra(ContactsContract.Intents.Insert.EMAIL, address)
        } else {
            putExtra(ContactsContract.Intents.Insert.PHONE, address)
        }

        // Only set name if it's different from the address (i.e., not just a number)
        if (name != address) {
            putExtra(ContactsContract.Intents.Insert.NAME, name)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle case where no contacts app is available
    }
}

/**
 * View an existing contact by looking up their phone number or email
 */
private fun viewContact(context: Context, address: String) {
    try {
        // Look up contact by phone number or email
        val contactUri = if (address.contains("@")) {
            // Email lookup
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        } else {
            // Phone number lookup
            Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        }

        val projection = arrayOf(ContactsContract.Contacts._ID)
        val cursor = context.contentResolver.query(contactUri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val contactViewUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactId.toString()
                )
                val intent = Intent(Intent.ACTION_VIEW, contactViewUri)
                context.startActivity(intent)
            }
        }
    } catch (e: Exception) {
        // Handle case where contact lookup fails or no contacts app is available
    }
}
