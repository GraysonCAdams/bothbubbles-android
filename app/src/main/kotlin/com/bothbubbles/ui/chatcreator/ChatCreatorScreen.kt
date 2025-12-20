package com.bothbubbles.ui.chatcreator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val isGroupMode = uiState.mode == ChatCreatorMode.GROUP

    // Contacts permission launcher - opens settings if permission was permanently denied
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshContactsPermission()
        if (!granted) {
            // Check if we should show rationale - if false, user permanently denied
            val activity = context.findActivity()
            val shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_CONTACTS)
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

    // Refresh permission when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContactsPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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

    // Handle back press in group mode - exit group mode instead of navigating back
    BackHandler(enabled = isGroupMode) {
        viewModel.exitGroupMode()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    AnimatedContent(
                        targetState = isGroupMode,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "title"
                    ) { inGroupMode ->
                        Text(
                            text = if (inGroupMode) "New group" else "New conversation",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isGroupMode) {
                                viewModel.exitGroupMode()
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isGroupMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isGroupMode) "Cancel" else "Back"
                        )
                    }
                },
                actions = {
                    // Show Next button when in group mode with 2+ recipients
                    AnimatedVisibility(
                        visible = isGroupMode && uiState.selectedRecipients.size >= 2,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        TextButton(
                            onClick = { viewModel.onContinue() },
                            enabled = !uiState.isLoading
                        ) {
                            Text("Next")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            // Show FAB for "Next" in group mode when 2+ recipients selected
            AnimatedVisibility(
                visible = isGroupMode && uiState.selectedRecipients.size >= 2,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.onContinue() },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null
                        )
                    },
                    text = { Text("Next") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // M3 Recipient field with inline chips
            M3RecipientField(
                recipients = uiState.selectedRecipients,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onRemoveRecipient = { viewModel.removeRecipient(it) },
                onRemoveLastRecipient = { viewModel.removeLastRecipient() },
                onDone = { viewModel.onDonePressed() },
                focusRequester = focusRequester,
                placeholder = if (isGroupMode) "Add participants..." else "Type name, phone number, or email",
                addMorePlaceholder = if (isGroupMode) "Add more participants..." else "Add another recipient..."
            )

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

            // Show permission card if contacts access is denied
            if (!uiState.hasContactsPermission && !uiState.isLoading) {
                ContactsPermissionCard(
                    onOpenSettings = {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                )
            }

            // Create list state and coroutine scope for fast scrolling
            // Use rememberSaveable to persist scroll position across process death
            val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val coroutineScope = rememberCoroutineScope()

            // Hide keyboard when scrolling starts (debounced to avoid spam)
            LaunchedEffect(listState) {
                var keyboardHidden = false
                snapshotFlow { listState.isScrollInProgress }
                    .collect { isScrolling ->
                        if (isScrolling && !keyboardHidden) {
                            keyboardController?.hide()
                            keyboardHidden = true
                        } else if (!isScrolling) {
                            keyboardHidden = false
                        }
                    }
            }

            // Build section index map for fast scrolling
            val sectionIndexMap = remember(uiState.recentContacts, uiState.favoriteContacts, uiState.groupedContacts, uiState.searchQuery, isGroupMode) {
                buildMap {
                    var index = 0
                    // Create group row (only in single mode, not searching)
                    if (!isGroupMode && uiState.searchQuery.isEmpty()) index++
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
                    contentPadding = PaddingValues(
                        bottom = if (isGroupMode && uiState.selectedRecipients.size >= 2) 88.dp else 16.dp,
                        end = if (availableLetters.size > 1) 24.dp else 0.dp
                    )
                ) {
                    // "Create group" action row (only in single mode when not searching)
                    if (!isGroupMode && uiState.searchQuery.isEmpty()) {
                        item(key = "create_group_action") {
                            CreateGroupListItem(
                                onClick = { viewModel.enterGroupMode() }
                            )
                        }
                    }

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
                                style = MaterialTheme.typography.labelMedium,
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
                                showCheckbox = isGroupMode,
                                onClick = {
                                    if (isGroupMode) {
                                        viewModel.toggleRecipient(contact)
                                    } else {
                                        // In single mode, directly start conversation
                                        viewModel.selectContact(contact)
                                    }
                                }
                            )
                        }
                    }

                    // "All Contacts" divider before alphabetical list
                    val hasMoreContacts = uiState.groupedContacts.isNotEmpty() || uiState.favoriteContacts.isNotEmpty()
                    if (hasMoreContacts && uiState.searchQuery.isEmpty()) {
                        item(key = "all_contacts_header") {
                            Text(
                                text = "All Contacts",
                                style = MaterialTheme.typography.labelMedium,
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
                                showCheckbox = isGroupMode,
                                onClick = {
                                    if (isGroupMode) {
                                        viewModel.toggleRecipient(contact)
                                    } else {
                                        viewModel.selectContact(contact)
                                    }
                                }
                            )
                        }
                    }

                    // Alphabetical sections
                    uiState.groupedContacts.forEach { (letter, contacts) ->
                        item(key = "letter_$letter") {
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.labelMedium,
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
                                showCheckbox = isGroupMode,
                                onClick = {
                                    if (isGroupMode) {
                                        viewModel.toggleRecipient(contact)
                                    } else {
                                        viewModel.selectContact(contact)
                                    }
                                }
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
 * Find the Activity from a Context by unwrapping ContextWrappers.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
