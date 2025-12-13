package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.common.Avatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatCreatorScreen(
    onBackClick: () -> Unit,
    onChatCreated: (String) -> Unit,
    onNavigateToGroupSetup: (participantsJson: String, groupService: String) -> Unit = { _, _ -> },
    viewModel: ChatCreatorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
                    android.util.Log.d("ChatCreator", "selectedRecipients: ${uiState.selectedRecipients.size}, isLoading: ${uiState.isLoading}")
                    if (uiState.selectedRecipients.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                android.util.Log.d("ChatCreator", "Continue button clicked!")
                                viewModel.onContinue()
                            },
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

            // Create list state and coroutine scope for fast scrolling
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            // Hide keyboard when scrolling starts
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) {
                    keyboardController?.hide()
                }
            }

            // Build section index map for fast scrolling
            val sectionIndexMap = remember(uiState.recentContacts, uiState.favoriteContacts, uiState.groupedContacts, uiState.searchQuery) {
                buildMap {
                    var index = 0
                    // Manual entry item
                    if (uiState.manualAddressEntry != null) index++
                    // Recent section
                    if (uiState.recentContacts.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                        index++ // header
                        index += uiState.recentContacts.size
                    }
                    // All Contacts header
                    if ((uiState.groupedContacts.isNotEmpty() || uiState.favoriteContacts.isNotEmpty()) && uiState.searchQuery.isEmpty()) {
                        index++
                    }
                    // Favorites - mark with star
                    if (uiState.favoriteContacts.isNotEmpty()) {
                        put("★", index)
                        index += uiState.favoriteContacts.size
                    }
                    // Alphabetical sections
                    uiState.groupedContacts.forEach { (letter, contacts) ->
                        put(letter, index)
                        index++ // header
                        index += contacts.size
                    }
                }
            }

            // Available letters for the fast scroller
            val availableLetters = remember(sectionIndexMap) {
                sectionIndexMap.keys.toList().sortedWith(compareBy {
                    if (it == "★") "" else it // Star goes first
                })
            }

            // Contacts list with fast scroller
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp, end = if (availableLetters.size > 1) 24.dp else 0.dp)
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

                // Recent section (up to 4 contacts with recent conversations)
                if (uiState.recentContacts.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item(key = "recent_header") {
                        Text(
                            text = "Recent",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = uiState.recentContacts,
                        key = { "${it.address}_recent" }
                    ) { contact ->
                        val isSelected = uiState.selectedRecipients.any { it.address == contact.address }
                        ContactTile(
                            contact = contact,
                            isSelected = isSelected,
                            onClick = { viewModel.toggleRecipient(contact) }
                        )
                    }
                }

                // "All Contacts" divider before alphabetical list
                val hasMoreContacts = uiState.groupedContacts.isNotEmpty() || uiState.favoriteContacts.isNotEmpty()
                if (hasMoreContacts && uiState.searchQuery.isEmpty()) {
                    item(key = "all_contacts_header") {
                        Text(
                            text = "All Contacts",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // Favorites section (if any)
                if (uiState.favoriteContacts.isNotEmpty()) {
                    items(
                        items = uiState.favoriteContacts,
                        key = { "${it.address}_fav" }
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
                    item(key = "letter_$letter") {
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

                // Empty state
                if (uiState.recentContacts.isEmpty() && uiState.groupedContacts.isEmpty() && uiState.favoriteContacts.isEmpty() && !uiState.isLoading) {
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

                // Fast scroll alphabet bar
                if (availableLetters.size > 1 && uiState.searchQuery.isEmpty()) {
                    AlphabetFastScroller(
                        letters = availableLetters,
                        onLetterSelected = { letter ->
                            sectionIndexMap[letter]?.let { index ->
                                coroutineScope.launch {
                                    listState.animateScrollToItem(index)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                    )
                }
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

                // Favorite indicator - star icon in top right
                if (contact.isFavorite && !isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Name and phone number
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = contact.formattedAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Protocol badge removed - service will be shown via chip color when selected
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
 * Material Design 3 style alphabet fast scroller for contacts list.
 * Shows letters on the right side that can be tapped or dragged to jump to sections.
 */
@Composable
private fun AlphabetFastScroller(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var containerHeight by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var currentLetter by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .onSizeChanged { containerHeight = it.height }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val letterIndex = (offset.y / containerHeight * letters.size).toInt()
                            .coerceIn(0, letters.size - 1)
                        currentLetter = letters[letterIndex]
                        onLetterSelected(letters[letterIndex])
                    },
                    onDragEnd = {
                        isDragging = false
                        currentLetter = null
                    },
                    onDragCancel = {
                        isDragging = false
                        currentLetter = null
                    },
                    onVerticalDrag = { change, _ ->
                        val letterIndex = (change.position.y / containerHeight * letters.size).toInt()
                            .coerceIn(0, letters.size - 1)
                        if (currentLetter != letters[letterIndex]) {
                            currentLetter = letters[letterIndex]
                            onLetterSelected(letters[letterIndex])
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                val isHighlighted = isDragging && currentLetter == letter
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (isHighlighted) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onLetterSelected(letter) },
                    contentAlignment = Alignment.Center
                ) {
                    if (letter == "★") {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorites",
                            tint = if (isHighlighted) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isHighlighted) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/**
 * UI model for displaying a contact in the list
 */
data class ContactUiModel(
    val address: String,
    val normalizedAddress: String,  // For de-duplication
    val formattedAddress: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false  // Whether this contact has recent conversations
)
