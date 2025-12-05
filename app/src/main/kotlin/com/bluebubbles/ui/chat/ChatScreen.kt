package com.bluebubbles.ui.chat

import android.Manifest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bluebubbles.util.PhoneNumberFormatter
import com.bluebubbles.R
import com.bluebubbles.ui.components.AttachmentPickerPanel
import com.bluebubbles.ui.components.Avatar
import com.bluebubbles.ui.components.MessageBubble
import com.bluebubbles.ui.components.MessageUiModel
import com.bluebubbles.ui.components.ScheduleMessageDialog
import com.bluebubbles.ui.components.TapbackMenu
import com.bluebubbles.ui.theme.BlueBubblesTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatGuid: String,
    onBackClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onMediaClick: (String) -> Unit,
    onCameraClick: () -> Unit = {},
    capturedPhotoUri: Uri? = null,
    onCapturedPhotoHandled: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Menu and dialog state
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showVideoCallDialog by remember { mutableStateOf(false) }

    // Attachment picker state
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    // Tapback menu state
    var selectedMessageForTapback by remember { mutableStateOf<MessageUiModel?>(null) }

    // Track pending attachments locally for UI
    var pendingAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Voice memo recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableLongStateOf(0L) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }

    // Check WhatsApp availability
    val isWhatsAppAvailable = remember { viewModel.isWhatsAppAvailable(context) }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Start recording after permission granted
            startVoiceMemoRecording(
                context = context,
                onRecorderCreated = { recorder, file ->
                    mediaRecorder = recorder
                    recordingFile = file
                    isRecording = true
                },
                onError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Toast.makeText(context, "Microphone permission required for voice memos", Toast.LENGTH_SHORT).show()
        }
    }

    // Recording duration timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0L
            while (isRecording) {
                kotlinx.coroutines.delay(100L)
                recordingDuration += 100L
            }
        }
    }

    // Cleanup recording on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.release()
        }
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            pendingAttachments = pendingAttachments + uri
            viewModel.addAttachment(uri)
        }
    }

    // Handle chat deletion - navigate back
    LaunchedEffect(uiState.chatDeleted) {
        if (uiState.chatDeleted) {
            onBackClick()
        }
    }

    // Handle captured photo from in-app camera
    LaunchedEffect(capturedPhotoUri) {
        capturedPhotoUri?.let { uri ->
            pendingAttachments = pendingAttachments + uri
            viewModel.addAttachment(uri)
            onCapturedPhotoHandled()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onDetailsClick)
                    ) {
                        Avatar(
                            name = uiState.chatTitle,
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (PhoneNumberFormatter.isPhoneNumber(uiState.chatTitle)) {
                                    PhoneNumberFormatter.format(uiState.chatTitle)
                                } else {
                                    uiState.chatTitle
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (uiState.isTyping) {
                                Text(
                                    text = stringResource(R.string.typing_indicator),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Video call button
                    IconButton(onClick = { showVideoCallDialog = true }) {
                        Icon(Icons.Outlined.Videocam, contentDescription = "Video call")
                    }

                    // Overflow menu button
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }

                        ChatOverflowMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            menuState = ChatMenuState(
                                isGroupChat = uiState.isGroup,
                                isArchived = uiState.isArchived,
                                isStarred = uiState.isStarred,
                                showSubjectField = uiState.showSubjectField,
                                isSmsChat = uiState.isLocalSmsChat
                            ),
                            onAction = { action ->
                                when (action) {
                                    ChatMenuAction.ADD_PEOPLE -> {
                                        context.startActivity(viewModel.getAddToContactsIntent())
                                    }
                                    ChatMenuAction.DETAILS -> onDetailsClick()
                                    ChatMenuAction.STARRED -> viewModel.toggleStarred()
                                    ChatMenuAction.SEARCH -> viewModel.activateSearch()
                                    ChatMenuAction.CHANGE_COLORS -> {
                                        // TODO: Implement change colors functionality
                                    }
                                    ChatMenuAction.ARCHIVE -> viewModel.archiveChat()
                                    ChatMenuAction.UNARCHIVE -> viewModel.unarchiveChat()
                                    ChatMenuAction.DELETE -> showDeleteDialog = true
                                    ChatMenuAction.BLOCK_AND_REPORT -> showBlockDialog = true
                                    ChatMenuAction.HELP_AND_FEEDBACK -> {
                                        context.startActivity(viewModel.getHelpIntent())
                                    }
                                }
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                // Attachment picker panel (slides up above input)
                AttachmentPickerPanel(
                    visible = showAttachmentPicker,
                    onDismiss = { showAttachmentPicker = false },
                    onAttachmentSelected = { uri ->
                        pendingAttachments = pendingAttachments + uri
                        viewModel.addAttachment(uri)
                    },
                    onLocationSelected = { lat, lng ->
                        // Format location as a shareable Google Maps link
                        val locationText = "📍 https://maps.google.com/?q=$lat,$lng"
                        viewModel.updateDraft(locationText)
                    },
                    onContactSelected = { name, phone ->
                        // Format contact as text to share
                        val contactText = "$name: $phone"
                        viewModel.updateDraft(contactText)
                    },
                    onScheduleClick = { showScheduleDialog = true },
                    onMagicComposeClick = {
                        // TODO: Implement AI-powered compose suggestions
                        Toast.makeText(context, "Magic Compose coming soon!", Toast.LENGTH_SHORT).show()
                    },
                    onCameraClick = onCameraClick
                )

                if (isRecording) {
                    VoiceMemoRecordingBar(
                        duration = recordingDuration,
                        onCancel = {
                            mediaRecorder?.release()
                            mediaRecorder = null
                            recordingFile?.delete()
                            recordingFile = null
                            isRecording = false
                        },
                        onSend = {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
                            recordingFile?.let { file ->
                                val uri = Uri.fromFile(file)
                                pendingAttachments = pendingAttachments + uri
                                viewModel.addAttachment(uri)
                                viewModel.sendMessage()
                                pendingAttachments = emptyList()
                            }
                            recordingFile = null
                        }
                    )
                } else {
                    MessageInputArea(
                        text = uiState.draftText,
                        onTextChange = viewModel::updateDraft,
                        onSendClick = {
                            viewModel.sendMessage()
                            pendingAttachments = emptyList()
                            showAttachmentPicker = false
                        },
                        onAttachClick = { showAttachmentPicker = !showAttachmentPicker },
                        onImageClick = { imagePickerLauncher.launch("image/*") },
                        onVoiceMemoClick = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        isSending = uiState.isSending,
                        isLocalSmsChat = uiState.isLocalSmsChat,
                        hasAttachments = pendingAttachments.isNotEmpty(),
                        attachments = pendingAttachments,
                        onRemoveAttachment = { uri ->
                            pendingAttachments = pendingAttachments - uri
                            viewModel.removeAttachment(uri)
                        },
                        isPickerExpanded = showAttachmentPicker
                    )
                }
            }
        }
    ) { padding ->
        // Auto-scroll to search result when navigating
        LaunchedEffect(uiState.currentSearchMatchIndex) {
            if (uiState.currentSearchMatchIndex >= 0 && uiState.searchMatchIndices.isNotEmpty()) {
                val messageIndex = uiState.searchMatchIndices[uiState.currentSearchMatchIndex]
                listState.animateScrollToItem(messageIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Inline search bar
            InlineSearchBar(
                visible = uiState.isSearchActive,
                query = uiState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                onClose = viewModel::closeSearch,
                onNavigateUp = viewModel::navigateSearchUp,
                onNavigateDown = viewModel::navigateSearchDown,
                currentMatch = if (uiState.searchMatchIndices.isNotEmpty()) uiState.currentSearchMatchIndex + 1 else 0,
                totalMatches = uiState.searchMatchIndices.size
            )

            // iOS-style sending indicator bar
            SendingIndicatorBar(
                isVisible = uiState.isSending,
                isLocalSmsChat = uiState.isLocalSmsChat
            )

            // Save contact banner for unsaved senders
            SaveContactBanner(
                visible = uiState.showSaveContactBanner,
                senderAddress = uiState.unsavedSenderAddress ?: "",
                onAddContact = {
                    context.startActivity(viewModel.getAddToContactsIntent())
                    viewModel.dismissSaveContactBanner()
                },
                onReportSpam = {
                    // Block the contact and dismiss
                    viewModel.blockContact(context)
                    viewModel.dismissSaveContactBanner()
                },
                onDismiss = viewModel::dismissSaveContactBanner
            )

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.messages.isEmpty() -> {
                    EmptyStateMessages(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, message -> message.guid }
                        ) { index, message ->
                            // Enable tapback for all messages with text content
                            // For iMessage: uses native tapback API
                            // For SMS/MMS: sends translated text like 'Loved "message"'
                            val canTapback = !message.text.isNullOrBlank()

                            // Check if this message is a search match or the current match
                            val isSearchMatch = uiState.isSearchActive && index in uiState.searchMatchIndices
                            val isCurrentSearchMatch = uiState.isSearchActive &&
                                uiState.currentSearchMatchIndex >= 0 &&
                                uiState.searchMatchIndices.getOrNull(uiState.currentSearchMatchIndex) == index

                            Box {
                                MessageBubble(
                                    message = message,
                                    onLongPress = {
                                        if (canTapback) {
                                            selectedMessageForTapback = message
                                        }
                                    },
                                    onMediaClick = onMediaClick,
                                    searchQuery = if (uiState.isSearchActive) uiState.searchQuery else null,
                                    isCurrentSearchMatch = isCurrentSearchMatch
                                )

                                // Show tapback menu for this message (positioned above)
                                if (selectedMessageForTapback?.guid == message.guid) {
                                    TapbackMenu(
                                        visible = true,
                                        onDismiss = { selectedMessageForTapback = null },
                                        onReactionSelected = { tapback ->
                                            viewModel.toggleReaction(message.guid, tapback)
                                        },
                                        myReactions = message.myReactions,
                                        isFromMe = message.isFromMe,
                                        onEmojiPickerClick = {
                                            // TODO: Show emoji picker for custom reactions
                                            selectedMessageForTapback = null
                                            Toast.makeText(context, "Custom emoji reactions coming soon!", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialogs
    if (showDeleteDialog) {
        DeleteConversationDialog(
            chatDisplayName = uiState.chatTitle,
            onConfirm = {
                viewModel.deleteChat()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showBlockDialog) {
        BlockAndReportDialog(
            chatDisplayName = uiState.chatTitle,
            isSmsChat = uiState.isLocalSmsChat,
            onConfirm = {
                if (viewModel.blockContact(context)) {
                    Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                }
                showBlockDialog = false
            },
            onDismiss = { showBlockDialog = false }
        )
    }

    if (showVideoCallDialog) {
        VideoCallMethodDialog(
            onGoogleMeet = {
                context.startActivity(viewModel.getGoogleMeetIntent())
            },
            onWhatsApp = {
                viewModel.getWhatsAppCallIntent()?.let { intent ->
                    context.startActivity(intent)
                }
            },
            onDismiss = { showVideoCallDialog = false },
            isWhatsAppAvailable = isWhatsAppAvailable
        )
    }

    // Schedule message dialog
    ScheduleMessageDialog(
        visible = showScheduleDialog,
        onDismiss = { showScheduleDialog = false },
        onSchedule = { timestamp ->
            // TODO: Implement scheduled message sending
            Toast.makeText(
                context,
                "Message scheduled for ${java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}",
                Toast.LENGTH_SHORT
            ).show()
        }
    )
}

/**
 * Message input area matching the screenshot design.
 * - Dark rounded text field with:
 *   - "+" attachment button on left (inside field)
 *   - Emoji and image buttons on right (inside field)
 * - Voice memo button on right for iMessage (replaced by send when text entered)
 */
@Composable
private fun MessageInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachClick: () -> Unit,
    onImageClick: () -> Unit,
    onVoiceMemoClick: () -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    hasAttachments: Boolean,
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
    isPickerExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isMmsMode = isLocalSmsChat && (hasAttachments || text.length > 160)
    val hasContent = text.isNotBlank() || hasAttachments
    val inputColors = BlueBubblesTheme.bubbleColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attachment previews
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments) { uri ->
                        AttachmentPreview(
                            uri = uri,
                            onRemove = { onRemoveAttachment(uri) }
                        )
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rounded text input field with inline buttons (Google Messages style)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = inputColors.inputFieldBackground
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Add attachment button (inside text field on left)
                        IconButton(
                            onClick = onAttachClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isPickerExpanded) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = stringResource(R.string.attach_file),
                                tint = if (isPickerExpanded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    inputColors.inputIcon
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Text input
                        TextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    text = stringResource(
                                        if (isLocalSmsChat) R.string.message_placeholder_text
                                        else R.string.message_placeholder_imessage
                                    ),
                                    color = inputColors.inputPlaceholder
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = inputColors.inputText,
                                unfocusedTextColor = inputColors.inputText,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 4
                        )

                        // Emoji button
                        IconButton(
                            onClick = { /* TODO: Emoji picker */ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.EmojiEmotions,
                                contentDescription = stringResource(R.string.emoji),
                                tint = inputColors.inputIcon,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Image button
                        IconButton(
                            onClick = onImageClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = stringResource(R.string.attach_image),
                                tint = inputColors.inputIcon,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Voice memo button (iMessage only) or Send button
                if (hasContent) {
                    // Show send button when there's content
                    SendButton(
                        onClick = onSendClick,
                        isSending = isSending,
                        isMmsMode = isMmsMode
                    )
                } else if (!isLocalSmsChat) {
                    // Show voice memo button for iMessage when no content
                    VoiceMemoButton(onClick = onVoiceMemoClick)
                }
            }
        }
    }
}

/**
 * Attachment preview thumbnail with remove button
 */
@Composable
private fun AttachmentPreview(
    uri: Uri,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = "Attachment",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Remove button overlay
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Send button with optional MMS indicator.
 * Uses MD3 FilledIconButton styling with contextual colors.
 */
@Composable
private fun SendButton(
    onClick: () -> Unit,
    isSending: Boolean,
    isMmsMode: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isMmsMode) {
        Color(0xFF34C759) // Green for SMS/MMS
    } else {
        MaterialTheme.colorScheme.primary
    }

    FilledIconButton(
        onClick = onClick,
        enabled = !isSending,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = containerColor.copy(alpha = 0.38f),
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        )
    ) {
        if (isSending) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            if (isMmsMode) {
                // Show MMS label below icon for SMS mode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send_message),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "MMS",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Voice memo button for iMessage sessions.
 * Uses MD3 FilledTonalIconButton for secondary action styling.
 */
@Composable
private fun VoiceMemoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp)
    ) {
        Icon(
            Icons.Outlined.Mic,
            contentDescription = stringResource(R.string.voice_memo),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Empty state when no messages in conversation
 */
@Composable
private fun EmptyStateMessages(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No messages yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Start the conversation by sending a message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * iOS-style sending indicator bar that appears at the top when sending a message.
 * Shows an indeterminate progress bar with "Sending..." text.
 */
@Composable
private fun SendingIndicatorBar(
    isVisible: Boolean,
    isLocalSmsChat: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = if (isLocalSmsChat) {
                Color(0xFF34C759).copy(alpha = 0.15f) // Green tint for SMS
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Progress bar
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isLocalSmsChat) {
                        Color(0xFF34C759) // Green for SMS
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = if (isLocalSmsChat) {
                        Color(0xFF34C759).copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    }
                )

                // Sending text
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isLocalSmsChat) "Sending SMS..." else "Sending...",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLocalSmsChat) {
                            Color(0xFF34C759)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    }
}

/**
 * Banner prompting user to save an unknown sender as a contact.
 * Shows for 1-on-1 chats with unsaved contacts, dismissible once per address.
 */
@Composable
private fun SaveContactBanner(
    visible: Boolean,
    senderAddress: String,
    onAddContact: () -> Unit,
    onReportSpam: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Add contact icon
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PersonAddAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Text content
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save $senderAddress?",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Saving this number will add a new contact",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Dismiss button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onReportSpam) {
                        Text("Report spam")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onAddContact) {
                        Text("Add contact")
                    }
                }
            }
        }
    }
}

/**
 * Voice memo recording bar that replaces the input area during recording.
 * Shows animated waveform, duration, and cancel/send buttons.
 */
@Composable
private fun VoiceMemoRecordingBar(
    duration: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val inputColors = BlueBubblesTheme.bubbleColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Recording indicator with waveform animation
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Red recording dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = Color.Red.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Waveform bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(20) { index ->
                        val barHeight by infiniteTransition.animateFloat(
                            initialValue = 4f,
                            targetValue = 20f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(
                                    durationMillis = 300 + (index * 50) % 200,
                                    easing = LinearEasing
                                ),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "bar_$index"
                        )

                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(barHeight.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(1.5.dp)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Duration
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.bodyMedium,
                    color = inputColors.inputText
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Send button - uses MD3 FilledIconButton
            FilledIconButton(
                onClick = onSend,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Starts voice memo recording using MediaRecorder.
 * Creates a temporary file and configures the recorder for audio capture.
 */
private fun startVoiceMemoRecording(
    context: android.content.Context,
    onRecorderCreated: (MediaRecorder, java.io.File) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val outputFile = java.io.File(
            context.cacheDir,
            "voice_memo_${System.currentTimeMillis()}.m4a"
        )

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        onRecorderCreated(recorder, outputFile)
    } catch (e: Exception) {
        onError("Failed to start recording: ${e.message}")
    }
}

/**
 * Inline search bar with navigation arrows, styled like Ctrl+F in browsers.
 * Shows at the top of the chat when search is active.
 */
@Composable
private fun InlineSearchBar(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    currentMatch: Int,
    totalMatches: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search icon
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )

                // Search text field
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Search messages",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true
                )

                // Match count indicator
                if (query.isNotBlank()) {
                    Text(
                        text = if (totalMatches > 0) "$currentMatch/$totalMatches" else "0/0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                // Navigate up button
                IconButton(
                    onClick = onNavigateUp,
                    enabled = totalMatches > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Previous match",
                        tint = if (totalMatches > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                // Navigate down button
                IconButton(
                    onClick = onNavigateDown,
                    enabled = totalMatches > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Next match",
                        tint = if (totalMatches > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                // Close button
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
