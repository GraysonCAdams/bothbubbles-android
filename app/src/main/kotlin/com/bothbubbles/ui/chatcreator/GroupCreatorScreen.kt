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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.common.Avatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreatorScreen(
    onBackClick: () -> Unit,
    onNextClick: (participantsJson: String, groupService: String) -> Unit,
    viewModel: GroupCreatorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field when screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New group",
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
                    // Next button (when 2+ participants selected)
                    if (uiState.selectedParticipants.size >= 2) {
                        TextButton(
                            onClick = {
                                val participantsJson = viewModel.getParticipantsJson()
                                onNextClick(participantsJson, uiState.groupService.name)
                            },
                            enabled = !uiState.isLoading
                        ) {
                            Text("Next")
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
            // Group service indicator
            if (uiState.selectedParticipants.isNotEmpty()) {
                GroupServiceIndicator(
                    groupService = uiState.groupService,
                    participantCount = uiState.selectedParticipants.size
                )
            }

            // Selected participants chips
            if (uiState.selectedParticipants.isNotEmpty()) {
                SelectedParticipantsRow(
                    participants = uiState.selectedParticipants,
                    onRemove = { viewModel.removeParticipant(it) }
                )
            }

            // Search/Add participants field
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
                        text = "Add:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
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
                                uiState.manualAddressEntry?.let { entry ->
                                    viewModel.addManualAddress(entry.address, entry.service)
                                }
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = "Type name, phone number, or email",
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

            Spacer(modifier = Modifier.height(8.dp))

            // Create list state and coroutine scope for fast scrolling
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            // Build section index map for fast scrolling
            val sectionIndexMap = remember(uiState.groupedContacts, uiState.searchQuery) {
                buildMap {
                    var index = 0
                    // Manual entry item
                    if (uiState.manualAddressEntry != null) index++
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
                sectionIndexMap.keys.toList().sorted()
            }

            // Contacts list with fast scroller
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp, end = if (availableLetters.size > 1) 24.dp else 0.dp)
                ) {
                    // Manual address entry option
                    uiState.manualAddressEntry?.let { entry ->
                        item(key = "manual_${entry.address}") {
                            AddParticipantTile(
                                address = entry.address,
                                service = entry.service,
                                isCheckingAvailability = uiState.isCheckingAvailability,
                                onClick = {
                                    viewModel.addManualAddress(entry.address, entry.service)
                                }
                            )
                        }
                    }

                    // Alphabetical contact sections
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
                            val isSelected = uiState.selectedParticipants.any { it.address == contact.address }
                            SelectableContactTile(
                                contact = contact,
                                isSelected = isSelected,
                                onClick = {
                                    viewModel.toggleParticipant(
                                        GroupParticipant(
                                            address = contact.address,
                                            displayName = contact.displayName,
                                            service = contact.service,
                                            avatarPath = contact.avatarPath
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // Empty state
                    if (uiState.groupedContacts.isEmpty() && uiState.manualAddressEntry == null && !uiState.isLoading) {
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
                    GroupAlphabetFastScroller(
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

    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show error and clear it
            viewModel.clearError()
        }
    }
}

@Composable
private fun GroupServiceIndicator(
    groupService: GroupServiceType,
    participantCount: Int
) {
    val (text, color) = when (groupService) {
        GroupServiceType.IMESSAGE -> "iMessage group" to MaterialTheme.colorScheme.primary
        GroupServiceType.MMS -> "SMS/MMS group" to MaterialTheme.colorScheme.secondary
        GroupServiceType.UNDETERMINED -> "Add participants" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$participantCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectedParticipantsRow(
    participants: List<GroupParticipant>,
    onRemove: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        participants.forEach { participant ->
            ParticipantChip(
                participant = participant,
                onRemove = { onRemove(participant.address) }
            )
        }
    }
}

@Composable
private fun ParticipantChip(
    participant: GroupParticipant,
    onRemove: () -> Unit
) {
    Surface(
        color = if (participant.service.equals("iMessage", ignoreCase = true)) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = participant.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = if (participant.service.equals("iMessage", ignoreCase = true)) {
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
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp),
                    tint = if (participant.service.equals("iMessage", ignoreCase = true)) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}

@Composable
private fun AddParticipantTile(
    address: String,
    service: String,
    isCheckingAvailability: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
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
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Add $address",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isCheckingAvailability) "Checking..." else "Add to group",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isCheckingAvailability) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = service,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (service == "iMessage") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SelectableContactTile(
    contact: ContactUiModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
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

                // Selection indicator
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Name and address
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
            // Protocol badge removed - service shown via chip color when selected
        }
    }
}

/**
 * Material Design 3 style alphabet fast scroller for group contacts list.
 */
@Composable
private fun GroupAlphabetFastScroller(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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
