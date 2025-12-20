package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            // Use rememberSaveable to persist scroll position across process death
            val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
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
