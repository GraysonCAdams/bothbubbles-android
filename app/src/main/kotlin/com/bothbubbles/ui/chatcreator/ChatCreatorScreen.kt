package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatCreatorScreen(
    onBackClick: () -> Unit,
    onChatCreated: (String) -> Unit,
    onNavigateToGroupSetup: (participantsJson: String, groupService: String) -> Unit = { _, _ -> },
    viewModel: ChatCreatorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the To field when screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(uiState.createdChatGuid) {
        uiState.createdChatGuid?.let { chatGuid ->
            onChatCreated(chatGuid)
            viewModel.resetCreatedChatGuid()
        }
    }

    // Handle navigation to group setup
    LaunchedEffect(uiState.navigateToGroupSetup) {
        uiState.navigateToGroupSetup?.let { nav ->
            onNavigateToGroupSetup(nav.participantsJson, nav.groupService)
            viewModel.resetGroupSetupNavigation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New chat",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Normal
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Show Continue button when recipients are selected
                    if (uiState.selectedRecipients.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.onContinue() },
                            enabled = !uiState.isLoading
                        ) {
                            Text("Continue")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Selected recipients chips row
            if (uiState.selectedRecipients.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.selectedRecipients.forEach { recipient ->
                        RecipientChip(
                            recipient = recipient,
                            onRemove = { viewModel.removeRecipient(recipient.address) }
                        )
                    }
                }
            }

            // Search/To field
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "To:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Track previous query for soft keyboard backspace detection
                    var previousQuery by remember { mutableStateOf("") }

                    BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = { newValue ->
                            // Detect backspace when previous was empty and still empty (soft keyboard workaround)
                            // This triggers when cursor is at position 0 and backspace is pressed
                            if (previousQuery.isEmpty() && newValue.isEmpty() && uiState.selectedRecipients.isNotEmpty()) {
                                // Soft keyboard sent empty -> empty, which can happen on some keyboards
                                // We'll rely on the onKeyEvent for this case
                            }
                            previousQuery = newValue
                            viewModel.updateSearchQuery(newValue)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onKeyEvent { event ->
                                // Handle backspace when input is empty to remove last recipient
                                // Only trigger on KeyDown to avoid double-firing
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Backspace &&
                                    uiState.searchQuery.isEmpty() &&
                                    uiState.selectedRecipients.isNotEmpty()
                                ) {
                                    viewModel.removeLastRecipient()
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                // Add current query as recipient if it's a valid phone/email
                                viewModel.onDonePressed()
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = if (uiState.selectedRecipients.isEmpty()) {
                                            "Type name, phone number, or email"
                                        } else {
                                            "Add another recipient..."
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            // Create group button - only show when 2+ recipients, or when no recipients selected (to discover feature)
            val showCreateGroupButton = uiState.selectedRecipients.size >= 2
            if (showCreateGroupButton) {
                Surface(
                    onClick = { viewModel.onContinue() },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.GroupAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Create group (${uiState.selectedRecipients.size} people)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Loading indicator
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Contacts list with sections
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Manual address entry option (when a valid phone number or email is typed)
                uiState.manualAddressEntry?.let { entry ->
                    item(key = "manual_address_${entry.address}") {
                        ManualAddressTile(
                            address = entry.address,
                            service = entry.service,
                            isCheckingAvailability = uiState.isCheckingAvailability,
                            onClick = {
                                viewModel.addManualRecipient(entry.address, entry.service)
                            }
                        )
                    }
                }

                // Favorites section (if any)
                if (uiState.favoriteContacts.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item {
                        Text(
                            text = "...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = uiState.favoriteContacts,
                        key = { "${it.address}_${it.service}_fav" }
                    ) { contact ->
                        val isSelected = uiState.selectedRecipients.any { it.address == contact.address }
                        ContactTile(
                            contact = contact,
                            isSelected = isSelected,
                            onClick = { viewModel.toggleRecipient(contact) }
                        )
                    }
                }

                // Alphabetical sections
                uiState.groupedContacts.forEach { (letter, contacts) ->
                    item {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = contacts,
                        key = { "${it.address}_${it.service}" }
                    ) { contact ->
                        val isSelected = uiState.selectedRecipients.any { it.address == contact.address }
                        ContactTile(
                            contact = contact,
                            isSelected = isSelected,
                            onClick = { viewModel.toggleRecipient(contact) }
                        )
                    }
                }

                // Group chats section
                if (uiState.groupChats.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Group chats",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = uiState.groupChats,
                        key = { it.guid }
                    ) { groupChat ->
                        GroupChatTile(
                            groupChat = groupChat,
                            onClick = { viewModel.selectGroupChat(groupChat) }
                        )
                    }
                }

                // Empty state
                if (uiState.groupedContacts.isEmpty() && uiState.favoriteContacts.isEmpty() && uiState.groupChats.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.searchQuery.isNotEmpty()) {
                                    "No contacts found"
                                } else {
                                    "No contacts"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Group chat tile showing name, last message preview, and time
 */
@Composable
private fun GroupChatTile(
    groupChat: GroupChatUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group avatar (use first letters or icon)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.GroupAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Name and last message
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupChat.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (groupChat.lastMessage != null) {
                    Text(
                        text = groupChat.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Time
            if (groupChat.lastMessageTime != null) {
                Text(
                    text = groupChat.lastMessageTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tile for starting a conversation with a manually entered address
 */
@Composable
private fun ManualAddressTile(
    address: String,
    service: String,
    isCheckingAvailability: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isCheckingAvailability, onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (service == "iMessage") {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = if (service == "iMessage") {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Send to $address",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = if (isCheckingAvailability) {
                        "Checking availability..."
                    } else {
                        "Start new conversation"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Service indicator or loading spinner
            if (isCheckingAvailability) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                val isIMessage = service == "iMessage"
                Surface(
                    color = if (isIMessage) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = service,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isIMessage) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactTile(
    contact: ContactUiModel,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = contact.service.equals("iMessage", ignoreCase = true)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            if (isIMessage) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            }
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(modifier = Modifier.size(48.dp)) {
                Avatar(
                    name = contact.displayName,
                    avatarPath = contact.avatarPath,
                    size = 48.dp
                )

                // Selection checkmark indicator
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isIMessage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = if (isIMessage) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Favorite indicator
                if (contact.isFavorite && !isSelected) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(18.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = "\uD83D\uDC9B", // Yellow heart emoji
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Name and phone number
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (contact.isFavorite) {
                        Text(
                            text = "\uD83D\uDC9B", // Yellow heart emoji
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = contact.formattedAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Service indicator with color coding
            val serviceText = contact.serviceLabel ?: if (isIMessage) "" else "SMS"
            if (serviceText.isNotEmpty()) {
                Surface(
                    color = if (isIMessage) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = serviceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isIMessage) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Chip displaying a selected recipient with platform color-coding
 */
@Composable
private fun RecipientChip(
    recipient: SelectedRecipient,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIMessage = recipient.service.equals("iMessage", ignoreCase = true)

    Surface(
        color = if (isIMessage) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = recipient.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = if (isIMessage) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${recipient.displayName}",
                    modifier = Modifier.size(16.dp),
                    tint = if (isIMessage) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}

/**
 * UI model for displaying a contact in the list
 */
data class ContactUiModel(
    val address: String,
    val formattedAddress: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isFavorite: Boolean = false,
    val serviceLabel: String? = null // "RCS", "SMS", etc.
)
