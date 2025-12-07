package com.bothbubbles.ui.chat

import android.Manifest
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.R
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.ui.components.AttachmentPickerPanel
import com.bothbubbles.ui.components.EmojiPickerPanel
import com.bothbubbles.ui.components.Avatar
import com.bothbubbles.ui.components.DateSeparator
import com.bothbubbles.ui.components.ForwardableChatInfo
import com.bothbubbles.ui.components.ForwardMessageDialog
import com.bothbubbles.ui.components.MessageBubble
import com.bothbubbles.ui.components.MessageGroupPosition
import com.bothbubbles.ui.components.MessageUiModel
import com.bothbubbles.ui.components.ScheduleMessageDialog
import com.bothbubbles.ui.components.SmartReplyChips
import com.bothbubbles.ui.components.SpamSafetyBanner
import com.bothbubbles.ui.components.TapbackMenu
import com.bothbubbles.ui.components.VCardOptionsDialog
import com.bothbubbles.ui.components.MessageListSkeleton
import com.bothbubbles.ui.components.staggeredEntrance
import com.bothbubbles.ui.effects.EffectPickerSheet
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.bubble.BubbleEffectWrapper
import com.bothbubbles.ui.effects.screen.ScreenEffectOverlay
import com.bothbubbles.ui.theme.BothBubblesTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    sharedText: String? = null,
    sharedUris: List<Uri> = emptyList(),
    onSharedContentHandled: () -> Unit = {},
    activateSearch: Boolean = false,
    onSearchActivated: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val smartReplySuggestions by viewModel.smartReplySuggestions.collectAsStateWithLifecycle()
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
    var showEmojiPicker by remember { mutableStateOf(false) }

    // vCard options dialog state
    var showVCardOptionsDialog by remember { mutableStateOf(false) }
    var pendingContactData by remember { mutableStateOf<VCardService.ContactData?>(null) }

    // Effect picker state
    var showEffectPicker by remember { mutableStateOf(false) }

    // Effect settings from ViewModel
    val autoPlayEffects by viewModel.autoPlayEffects.collectAsStateWithLifecycle()
    val replayEffectsOnScroll by viewModel.replayEffectsOnScroll.collectAsStateWithLifecycle()
    val reduceMotion by viewModel.reduceMotion.collectAsStateWithLifecycle()
    val activeScreenEffectState by viewModel.activeScreenEffect.collectAsStateWithLifecycle()

    // Attachment download settings and progress
    val autoDownloadEnabled by viewModel.autoDownloadEnabled.collectAsStateWithLifecycle()
    val downloadingAttachments by viewModel.attachmentDownloadProgress.collectAsStateWithLifecycle()

    // Track processed screen effects this session to avoid re-triggering
    val processedEffectMessages = remember { mutableSetOf<String>() }

    // Tapback menu state
    var selectedMessageForTapback by remember { mutableStateOf<MessageUiModel?>(null) }

    // Failed message retry menu state
    var selectedMessageForRetry by remember { mutableStateOf<MessageUiModel?>(null) }
    var canRetrySmsForMessage by remember { mutableStateOf(false) }
    val retryMenuScope = rememberCoroutineScope()

    // Forward message dialog state
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<MessageUiModel?>(null) }
    val forwardableChats by viewModel.getForwardableChats().collectAsStateWithLifecycle(initialValue = emptyList())

    // Track pending attachments locally for UI
    var pendingAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Voice memo recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableLongStateOf(0L) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }

    // Voice memo preview/playback state
    var isPreviewingVoiceMemo by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlayingVoiceMemo by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var playbackDuration by remember { mutableLongStateOf(0L) }

    // Audio amplitude history for waveform visualization (stores last 20 amplitude values)
    var amplitudeHistory by remember { mutableStateOf(List(20) { 0f }) }

    // Recording feedback sounds
    val mediaActionSound = remember {
        MediaActionSound().apply {
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

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
                    mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
                },
                onError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Toast.makeText(context, "Microphone permission required for voice memos", Toast.LENGTH_SHORT).show()
        }
    }

    // Recording duration timer with amplitude tracking
    LaunchedEffect(isRecording, mediaRecorder) {
        if (isRecording && mediaRecorder != null) {
            recordingDuration = 0L
            amplitudeHistory = List(20) { 0f }
            while (isRecording) {
                kotlinx.coroutines.delay(100L)
                recordingDuration += 100L
                // Capture amplitude for waveform visualization
                try {
                    val amplitude = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize to 0-1 range (maxAmplitude can be up to 32767)
                    val normalized = (amplitude / 32767f).coerceIn(0f, 1f)
                    amplitudeHistory = amplitudeHistory.drop(1) + normalized
                } catch (_: Exception) {
                    // Recorder may have been released
                }
            }
        }
    }

    // Playback position tracker
    LaunchedEffect(isPlayingVoiceMemo, mediaPlayer) {
        if (isPlayingVoiceMemo && mediaPlayer != null) {
            while (isPlayingVoiceMemo) {
                try {
                    playbackPosition = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    if (mediaPlayer?.isPlaying == false) {
                        isPlayingVoiceMemo = false
                        playbackPosition = 0L
                    }
                } catch (_: Exception) {
                    isPlayingVoiceMemo = false
                }
                kotlinx.coroutines.delay(50L)
            }
        }
    }

    // Cleanup recording and playback on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.release()
            mediaPlayer?.release()
            mediaActionSound.release()
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

    // Add contact launcher - refresh contact info when returning from contacts app
    val addContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh contact info when returning from contacts app (regardless of result)
        viewModel.refreshContactInfo()
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

    // Handle shared content from share picker
    LaunchedEffect(sharedText, sharedUris) {
        // Add shared URIs as attachments
        if (sharedUris.isNotEmpty()) {
            sharedUris.forEach { uri ->
                pendingAttachments = pendingAttachments + uri
                viewModel.addAttachment(uri)
            }
        }
        // Set shared text as draft
        if (sharedText != null) {
            viewModel.updateDraft(sharedText)
        }
        // Mark shared content as handled
        if (sharedText != null || sharedUris.isNotEmpty()) {
            onSharedContentHandled()
        }
    }

    // Handle search activation from ChatDetails screen
    LaunchedEffect(activateSearch) {
        if (activateSearch) {
            viewModel.activateSearch()
            onSearchActivated()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
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
                                if (uiState.isSnoozed) {
                                    Icon(
                                        Icons.Outlined.Snooze,
                                        contentDescription = "Snoozed",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
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
                    onContactSelected = { contactUri ->
                        // Get contact data and show options dialog
                        val contactData = viewModel.getContactData(contactUri)
                        if (contactData != null) {
                            pendingContactData = contactData
                            showVCardOptionsDialog = true
                        } else {
                            Toast.makeText(context, "Failed to read contact", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onScheduleClick = { showScheduleDialog = true },
                    onCameraClick = onCameraClick
                )

                // Emoji picker panel (slides up above input)
                EmojiPickerPanel(
                    visible = showEmojiPicker,
                    onDismiss = { showEmojiPicker = false },
                    onEmojiSelected = { emoji ->
                        viewModel.updateDraft(uiState.draftText + emoji)
                    }
                )

                if (isRecording) {
                    VoiceMemoRecordingBar(
                        duration = recordingDuration,
                        amplitudeHistory = amplitudeHistory,
                        onCancel = {
                            // Stop recording and enter preview mode
                            try {
                                mediaRecorder?.stop()
                                mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                            } catch (_: Exception) {
                                // May throw if no audio was recorded
                            }
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false

                            // Enter preview mode
                            recordingFile?.let { file ->
                                if (file.exists() && file.length() > 0) {
                                    isPreviewingVoiceMemo = true
                                    playbackDuration = recordingDuration
                                } else {
                                    file.delete()
                                    recordingFile = null
                                }
                            }
                        },
                        onSend = {
                            mediaRecorder?.stop()
                            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
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
                } else if (isPreviewingVoiceMemo) {
                    VoiceMemoPreviewBar(
                        duration = playbackDuration,
                        playbackPosition = playbackPosition,
                        isPlaying = isPlayingVoiceMemo,
                        onPlayPause = {
                            if (isPlayingVoiceMemo) {
                                // Pause playback
                                mediaPlayer?.pause()
                                isPlayingVoiceMemo = false
                            } else {
                                // Start or resume playback
                                if (mediaPlayer == null) {
                                    recordingFile?.let { file ->
                                        mediaPlayer = MediaPlayer().apply {
                                            setDataSource(file.absolutePath)
                                            prepare()
                                            start()
                                        }
                                        playbackDuration = mediaPlayer?.duration?.toLong() ?: recordingDuration
                                    }
                                } else {
                                    mediaPlayer?.start()
                                }
                                isPlayingVoiceMemo = true
                            }
                        },
                        onReRecord = {
                            // Stop and release player
                            mediaPlayer?.release()
                            mediaPlayer = null
                            isPlayingVoiceMemo = false
                            playbackPosition = 0L

                            // Delete old recording
                            recordingFile?.delete()
                            recordingFile = null
                            isPreviewingVoiceMemo = false

                            // Start new recording
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSend = {
                            // Stop player if playing
                            mediaPlayer?.release()
                            mediaPlayer = null
                            isPlayingVoiceMemo = false
                            playbackPosition = 0L
                            isPreviewingVoiceMemo = false

                            recordingFile?.let { file ->
                                val uri = Uri.fromFile(file)
                                pendingAttachments = pendingAttachments + uri
                                viewModel.addAttachment(uri)
                                viewModel.sendMessage()
                                pendingAttachments = emptyList()
                            }
                            recordingFile = null
                        },
                        onCancel = {
                            // Exit preview mode and delete recording
                            mediaPlayer?.release()
                            mediaPlayer = null
                            isPlayingVoiceMemo = false
                            playbackPosition = 0L
                            isPreviewingVoiceMemo = false
                            recordingFile?.delete()
                            recordingFile = null
                        }
                    )
                } else {
                    Column {
                        // Smart reply suggestions (ML Kit + user templates, max 3, right-aligned)
                        SmartReplyChips(
                            suggestions = smartReplySuggestions,
                            onSuggestionClick = { suggestion ->
                                viewModel.updateDraft(suggestion.text)
                                suggestion.templateId?.let { viewModel.recordTemplateUsage(it) }
                            }
                        )

                        MessageInputArea(
                            text = uiState.draftText,
                            onTextChange = viewModel::updateDraft,
                            onSendClick = {
                                viewModel.sendMessage()
                                pendingAttachments = emptyList()
                                showAttachmentPicker = false
                                showEmojiPicker = false
                            },
                        onSendLongPress = {
                            // Open effect picker on long press (iMessage only)
                            if (!uiState.isLocalSmsChat) {
                                showEffectPicker = true
                            }
                        },
                        onAttachClick = {
                            showAttachmentPicker = !showAttachmentPicker
                            if (showAttachmentPicker) showEmojiPicker = false
                        },
                        onEmojiClick = {
                            showEmojiPicker = !showEmojiPicker
                            if (showEmojiPicker) showAttachmentPicker = false
                        },
                        onImageClick = { imagePickerLauncher.launch("image/*") },
                        onVoiceMemoClick = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onVoiceMemoPressStart = {
                            // Start recording on press (if permission granted)
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                startVoiceMemoRecording(
                                    context = context,
                                    onRecorderCreated = { recorder, file ->
                                        mediaRecorder = recorder
                                        recordingFile = file
                                        isRecording = true
                                        mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
                                    },
                                    onError = { error ->
                                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        onVoiceMemoPressEnd = {
                            // Stop recording on release and enter preview mode
                            if (isRecording) {
                                try {
                                    mediaRecorder?.stop()
                                    mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                                } catch (_: Exception) {
                                    // May throw if no audio was recorded
                                }
                                mediaRecorder?.release()
                                mediaRecorder = null
                                isRecording = false

                                // Enter preview mode
                                recordingFile?.let { file ->
                                    if (file.exists() && file.length() > 0) {
                                        isPreviewingVoiceMemo = true
                                        playbackDuration = recordingDuration
                                    } else {
                                        file.delete()
                                        recordingFile = null
                                    }
                                }
                            }
                        },
                        isSending = uiState.isSending,
                        isLocalSmsChat = uiState.isLocalSmsChat,
                        hasAttachments = pendingAttachments.isNotEmpty(),
                        attachments = pendingAttachments,
                        onRemoveAttachment = { uri ->
                            pendingAttachments = pendingAttachments - uri
                            viewModel.removeAttachment(uri)
                        },
                        onClearAllAttachments = {
                            pendingAttachments = emptyList()
                            viewModel.clearAttachments()
                        },
                        isPickerExpanded = showAttachmentPicker,
                        isEmojiPickerExpanded = showEmojiPicker
                    )
                    }
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

        // Auto-scroll to show newest message when it arrives (if user is viewing recent messages)
        // This ensures tall content like link previews isn't clipped by the keyboard
        LaunchedEffect(uiState.messages.firstOrNull()?.guid) {
            val isNearBottom = listState.firstVisibleItemIndex <= 2
            if (uiState.messages.isNotEmpty() && isNearBottom) {
                // Small delay to let the message render and calculate its height
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(0)
            }
        }

        // Detect new messages with screen effects and trigger playback
        LaunchedEffect(uiState.messages.firstOrNull()?.guid) {
            val newest = uiState.messages.firstOrNull() ?: return@LaunchedEffect
            // Skip if already processed this session
            if (newest.guid in processedEffectMessages) return@LaunchedEffect
            // Skip if effects disabled or reduce motion enabled
            if (!autoPlayEffects || reduceMotion) return@LaunchedEffect
            // Skip if already played (and not replaying on scroll)
            if (newest.effectPlayed && !replayEffectsOnScroll) return@LaunchedEffect

            val effect = MessageEffect.fromStyleId(newest.expressiveSendStyleId)
            if (effect is MessageEffect.Screen) {
                processedEffectMessages.add(newest.guid)
                viewModel.triggerScreenEffect(newest)
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
                isLocalSmsChat = uiState.isLocalSmsChat || uiState.isInSmsFallbackMode
            )

            // SMS fallback mode banner
            SmsFallbackBanner(
                visible = uiState.isInSmsFallbackMode && !uiState.isLocalSmsChat,
                fallbackReason = uiState.fallbackReason,
                isServerConnected = uiState.isServerConnected,
                showExitAction = uiState.isIMessageChat,
                onExitFallback = viewModel::exitSmsFallback
            )

            // Save contact banner for unsaved senders
            SaveContactBanner(
                visible = uiState.showSaveContactBanner,
                senderAddress = uiState.unsavedSenderAddress ?: "",
                inferredName = uiState.inferredSenderName,
                onAddContact = {
                    addContactLauncher.launch(viewModel.getAddToContactsIntent())
                },
                onReportSpam = {
                    // Report as spam and optionally block the contact
                    viewModel.reportAsSpam()
                    if (uiState.isLocalSmsChat) {
                        viewModel.blockContact(context)
                    }
                    viewModel.dismissSaveContactBanner()
                },
                onDismiss = viewModel::dismissSaveContactBanner
            )

            when {
                uiState.isLoading -> {
                    MessageListSkeleton(
                        count = 10,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                uiState.messages.isEmpty() -> {
                    EmptyStateMessages(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    // Animate top padding when save contact banner is shown
                    // Extra padding accounts for reaction badges that extend above messages
                    val bannerTopPadding by animateDpAsState(
                        targetValue = if (uiState.showSaveContactBanner) 24.dp else 8.dp,
                        animationSpec = tween(durationMillis = 300),
                        label = "banner_padding"
                    )

                    // Get IME (keyboard) height to add extra scroll space when keyboard is visible
                    val imeInsets = WindowInsets.ime
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val imeHeight = with(density) { imeInsets.getBottom(density).toDp() }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = bannerTopPadding,
                            // Add IME height to ensure content can scroll above keyboard
                            bottom = 8.dp + imeHeight
                        )
                        // Spacing is handled per-item based on group position
                    ) {
                        // Spam safety banner - shows at the bottom when chat is marked as spam
                        // Since reverseLayout=true, adding at start puts it at visual bottom
                        if (uiState.isSpam) {
                            item(key = "spam_safety_banner") {
                                SpamSafetyBanner(
                                    onMarkAsSafe = { viewModel.markAsSafe() }
                                )
                            }
                        }

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

                            // Check for time gap with next visible message (previous in chronological order)
                            // Since list is reversed, next index = earlier message
                            // Skip reaction messages (they're hidden) when finding next message
                            // Also don't show separators for reaction messages themselves
                            val nextVisibleMessage = uiState.messages
                                .drop(index + 1)
                                .firstOrNull { !it.isReaction }
                            // Show separator if:
                            // 1. There's a 15+ minute gap with the next message, OR
                            // 2. This is the oldest message in the list (no next message) - always show a separator
                            val showTimeSeparator = !message.isReaction && (nextVisibleMessage?.let {
                                shouldShowTimeSeparator(message.dateCreated, it.dateCreated)
                            } ?: true)

                            // Calculate group position for visual message grouping
                            // In reversed layout: index 0 = newest (bottom), higher index = older (top)
                            val groupPosition = calculateGroupPosition(
                                messages = uiState.messages,
                                index = index,
                                message = message
                            )

                            // iMessage-style delivery indicator: only show on the last message
                            // in a consecutive sequence of outgoing messages
                            val showDeliveryIndicator = if (message.isFromMe) {
                                // Find the next non-reaction message (newer = lower index)
                                val newerMessage = uiState.messages
                                    .take(index)
                                    .lastOrNull { !it.isReaction }
                                // Show indicator only if no newer outgoing message exists
                                newerMessage?.isFromMe != true
                            } else {
                                false
                            }

                            // Spacing based on group position:
                            // - SINGLE/FIRST: 6dp top (gap between groups)
                            // - MIDDLE/LAST: 2dp top (tight within group)
                            val topPadding = when (groupPosition) {
                                MessageGroupPosition.SINGLE, MessageGroupPosition.FIRST -> 6.dp
                                MessageGroupPosition.MIDDLE, MessageGroupPosition.LAST -> 2.dp
                            }

                            Column(
                                modifier = Modifier
                                    .padding(top = topPadding)
                                    .staggeredEntrance(index)
                                    .animateItem()
                            ) {
                                // Show centered time separator BEFORE the message
                                // In reversed layout, this makes it appear above the message visually
                                if (showTimeSeparator) {
                                    DateSeparator(
                                        date = formatTimeSeparator(message.dateCreated)
                                    )
                                }

                                Box {
                                    // Parse bubble effect from expressiveSendStyleId
                                    val bubbleEffect = remember(message.expressiveSendStyleId) {
                                        MessageEffect.fromStyleId(message.expressiveSendStyleId) as? MessageEffect.Bubble
                                    }

                                    // Determine if this bubble should animate
                                    val shouldAnimateBubble = bubbleEffect != null &&
                                        autoPlayEffects && !reduceMotion &&
                                        (!message.effectPlayed || replayEffectsOnScroll)

                                    BubbleEffectWrapper(
                                        effect = bubbleEffect,
                                        isNewMessage = shouldAnimateBubble,
                                        isFromMe = message.isFromMe,
                                        onEffectComplete = { viewModel.onBubbleEffectCompleted(message.guid) },
                                        onReveal = { viewModel.onBubbleEffectCompleted(message.guid) }
                                    ) {
                                        MessageBubble(
                                            message = message,
                                            onLongPress = {
                                                if (message.hasError && message.isFromMe) {
                                                    // For failed messages, show retry menu
                                                    selectedMessageForRetry = message
                                                    // Check if SMS retry is available
                                                    retryMenuScope.launch {
                                                        canRetrySmsForMessage = viewModel.canRetryAsSms(message.guid)
                                                    }
                                                } else if (canTapback) {
                                                    selectedMessageForTapback = message
                                                }
                                            },
                                            onMediaClick = onMediaClick,
                                            groupPosition = groupPosition,
                                            searchQuery = if (uiState.isSearchActive) uiState.searchQuery else null,
                                            isCurrentSearchMatch = isCurrentSearchMatch,
                                            // Manual download mode: pass callback when auto-download is disabled
                                            onDownloadClick = if (!autoDownloadEnabled) {
                                                { attachmentGuid -> viewModel.downloadAttachment(attachmentGuid) }
                                            } else null,
                                            downloadingAttachments = downloadingAttachments,
                                            showDeliveryIndicator = showDeliveryIndicator
                                        )
                                    }

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
                                            onCopyClick = message.text?.let { text ->
                                                {
                                                    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Message", text))
                                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onForwardClick = {
                                                messageToForward = message
                                                showForwardDialog = true
                                            }
                                        )
                                    }

                                    // Show retry menu for failed messages
                                    if (selectedMessageForRetry?.guid == message.guid) {
                                        FailedMessageMenu(
                                            visible = true,
                                            onDismiss = { selectedMessageForRetry = null },
                                            onRetry = {
                                                viewModel.retryMessage(message.guid)
                                                selectedMessageForRetry = null
                                            },
                                            onRetryAsSms = if (canRetrySmsForMessage) {
                                                {
                                                    viewModel.retryMessageAsSms(message.guid)
                                                    selectedMessageForRetry = null
                                                }
                                            } else null,
                                            isFromMe = message.isFromMe
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Screen effect overlay (above all other content) - connected to ViewModel
    ScreenEffectOverlay(
        effect = activeScreenEffectState?.effect,
        messageText = activeScreenEffectState?.messageText,
        onEffectComplete = {
            viewModel.onScreenEffectCompleted()
        }
    )
    } // End of outer Box

    // Effect picker bottom sheet
    if (showEffectPicker) {
        EffectPickerSheet(
            messageText = uiState.draftText,
            onEffectSelected = { effect ->
                showEffectPicker = false
                if (effect != null) {
                    // Send message with effect
                    // Screen effect will trigger automatically when the message appears in the list
                    viewModel.sendMessage(effect.appleId)
                    pendingAttachments = emptyList()
                    showAttachmentPicker = false
                    showEmojiPicker = false
                }
            },
            onDismiss = { showEffectPicker = false }
        )
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
            onConfirm = { options ->
                // Handle block contact
                if (options.blockContact) {
                    if (viewModel.blockContact(context)) {
                        Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                    }
                }

                // Handle mark as spam
                if (options.markAsSpam) {
                    viewModel.reportAsSpam()
                    Toast.makeText(context, "Marked as spam", Toast.LENGTH_SHORT).show()
                }

                // Handle report to carrier
                if (options.reportToCarrier) {
                    if (viewModel.reportToCarrier()) {
                        Toast.makeText(context, "Reporting to carrier...", Toast.LENGTH_SHORT).show()
                    }
                }

                showBlockDialog = false
            },
            onDismiss = { showBlockDialog = false },
            alreadyReportedToCarrier = uiState.isReportedToCarrier
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
            // Schedule the message
            viewModel.scheduleMessage(
                text = uiState.draftText,
                attachments = pendingAttachments,
                sendAt = timestamp
            )

            // Clear the draft and attachments
            viewModel.updateDraft("")
            pendingAttachments = emptyList()

            // Show confirmation with disclaimer
            val dateFormat = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault())
            Toast.makeText(
                context,
                "Scheduled for ${dateFormat.format(java.util.Date(timestamp))}. Phone must be on to send.",
                Toast.LENGTH_LONG
            ).show()

            showScheduleDialog = false
        }
    )

    // vCard options dialog
    VCardOptionsDialog(
        visible = showVCardOptionsDialog,
        contactData = pendingContactData,
        onDismiss = {
            showVCardOptionsDialog = false
            pendingContactData = null
        },
        onConfirm = { options ->
            pendingContactData?.let { contactData ->
                val success = viewModel.addContactAsVCard(contactData, options)
                if (!success) {
                    Toast.makeText(context, "Failed to create contact card", Toast.LENGTH_SHORT).show()
                }
            }
            showVCardOptionsDialog = false
            pendingContactData = null
        }
    )

    // Forward message dialog
    ForwardMessageDialog(
        visible = showForwardDialog,
        onDismiss = {
            showForwardDialog = false
            messageToForward = null
        },
        onChatSelected = { targetChatGuid ->
            messageToForward?.let { message ->
                viewModel.forwardMessage(message.guid, targetChatGuid)
            }
        },
        chats = forwardableChats.map { chat ->
            ForwardableChatInfo(
                guid = chat.guid,
                displayName = chat.displayName ?: chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: "",
                isGroup = chat.isGroup
            )
        },
        isForwarding = uiState.isForwarding
    )

    // Handle forward success
    LaunchedEffect(uiState.forwardSuccess) {
        if (uiState.forwardSuccess) {
            Toast.makeText(context, "Message forwarded", Toast.LENGTH_SHORT).show()
            showForwardDialog = false
            messageToForward = null
            viewModel.clearForwardSuccess()
        }
    }
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
    onSendLongPress: () -> Unit = {},
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onImageClick: () -> Unit,
    onVoiceMemoClick: () -> Unit,
    onVoiceMemoPressStart: () -> Unit,
    onVoiceMemoPressEnd: () -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    hasAttachments: Boolean,
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
    onClearAllAttachments: () -> Unit,
    isPickerExpanded: Boolean = false,
    isEmojiPickerExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isMmsMode = isLocalSmsChat && (hasAttachments || text.length > 160)
    val hasContent = text.isNotBlank() || hasAttachments
    val inputColors = BothBubblesTheme.bubbleColors

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
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header with count and Clear All button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = inputColors.inputText.copy(alpha = 0.7f)
                        )

                        if (attachments.size > 1) {
                            TextButton(
                                onClick = onClearAllAttachments,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Clear All",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Attachment thumbnails
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                            onClick = onEmojiClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.EmojiEmotions,
                                contentDescription = stringResource(R.string.emoji),
                                tint = if (isEmojiPickerExpanded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    inputColors.inputIcon
                                },
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

                // Voice memo button or Send button (animated transition)
                Crossfade(
                    targetState = hasContent,
                    label = "input_action_button"
                ) { showSend ->
                    if (showSend) {
                        // Show send button when there's content
                        SendButton(
                            onClick = onSendClick,
                            onLongPress = onSendLongPress,
                            isSending = isSending,
                            isSmsMode = isLocalSmsChat,
                            isMmsMode = isMmsMode,
                            showEffectHint = !isLocalSmsChat
                        )
                    } else {
                        // Show voice memo button for all threads when no content
                        VoiceMemoButton(
                            onClick = onVoiceMemoClick,
                            onPressStart = onVoiceMemoPressStart,
                            onPressEnd = onVoiceMemoPressEnd,
                            isSmsMode = isLocalSmsChat
                        )
                    }
                }
            }
        }
    }
}

/**
 * Attachment preview thumbnail with remove button, file size, and video duration.
 */
@Composable
private fun AttachmentPreview(
    uri: Uri,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Get file info
    val fileInfo = remember(uri) {
        getAttachmentInfo(context, uri)
    }

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

        // Semi-transparent gradient overlay at bottom for text visibility
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )

        // File size at bottom left
        Text(
            text = fileInfo.formattedSize,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 4.dp)
        )

        // Video duration badge at bottom right (for videos)
        if (fileInfo.isVideo && fileInfo.durationFormatted != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = fileInfo.durationFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }

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
 * Information about an attachment (file size, video duration, etc.)
 */
private data class AttachmentInfo(
    val sizeBytes: Long,
    val formattedSize: String,
    val isVideo: Boolean,
    val durationMs: Long? = null,
    val durationFormatted: String? = null
)

/**
 * Get attachment info from a URI (file size, video duration, etc.)
 */
private fun getAttachmentInfo(context: android.content.Context, uri: Uri): AttachmentInfo {
    var sizeBytes = 0L
    var isVideo = false
    var durationMs: Long? = null

    try {
        // Get MIME type
        val mimeType = context.contentResolver.getType(uri)
        isVideo = mimeType?.startsWith("video/") == true

        // Get file size
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        // Get video duration if it's a video
        if (isVideo) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull()
                retriever.release()
            } catch (e: Exception) {
                // Ignore errors getting video duration
            }
        }
    } catch (e: Exception) {
        // Ignore errors
    }

    return AttachmentInfo(
        sizeBytes = sizeBytes,
        formattedSize = formatFileSize(sizeBytes),
        isVideo = isVideo,
        durationMs = durationMs,
        durationFormatted = durationMs?.let { formatDuration(it) }
    )
}

/**
 * Format file size for display (e.g., "1.5 MB")
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Format duration in milliseconds to a readable string (e.g., "1:30")
 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Send button with protocol-based coloring.
 * Green background for SMS/MMS, blue for iMessage.
 * Long press opens the effect picker for iMessage.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    isSending: Boolean,
    isSmsMode: Boolean,
    isMmsMode: Boolean,
    showEffectHint: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Protocol-based coloring: green for SMS, blue for iMessage
    val containerColor = if (isSmsMode) {
        Color(0xFF34C759) // Green for SMS/MMS
    } else {
        MaterialTheme.colorScheme.primary // Blue for iMessage
    }
    val contentColor = Color.White

    // Press feedback animation
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !isSending) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "sendButtonScale"
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (isSending) containerColor.copy(alpha = 0.38f) else containerColor
            )
            .pointerInput(!isSending) {
                if (!isSending) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                        onLongPress = { onLongPress() }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSending) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
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
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "MMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Voice memo button with soundwave icon.
 * Protocol-colored: green for SMS, blue for iMessage.
 * Hold to record, release to stop. Tap requests permission only.
 */
@Composable
private fun VoiceMemoButton(
    onClick: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    isSmsMode: Boolean,
    modifier: Modifier = Modifier
) {
    // Protocol-based coloring: green for SMS, blue for iMessage
    val containerColor = if (isSmsMode) {
        Color(0xFF34C759) // Green for SMS/MMS
    } else {
        MaterialTheme.colorScheme.primary // Blue for iMessage
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for initial press
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    // Track timing and state
                    val holdThresholdMs = 200L
                    val pressStartTime = System.currentTimeMillis()
                    var recordingStarted = false

                    // Wait for finger lift while tracking hold duration
                    do {
                        val event = awaitPointerEvent()
                        val elapsed = System.currentTimeMillis() - pressStartTime

                        // Start recording once hold threshold is reached
                        if (elapsed >= holdThresholdMs && !recordingStarted) {
                            recordingStarted = true
                            onPressStart()
                        }

                        // Consume all changes to prevent event leaking
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })

                    // Finger lifted
                    if (recordingStarted) {
                        // Was recording - stop it
                        onPressEnd()
                    } else {
                        // Quick tap - just request permission
                        onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(R.string.voice_memo),
            modifier = Modifier.size(24.dp),
            tint = Color.White
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
    inferredName: String? = null,
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
                            text = if (inferredName != null) {
                                "Save $inferredName?"
                            } else {
                                "Save ${PhoneNumberFormatter.format(senderAddress)}?"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (inferredName != null) {
                                "Add ${PhoneNumberFormatter.format(senderAddress)} as a contact"
                            } else {
                                "Saving this number will add a new contact"
                            },
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
 * Banner shown when chat is in SMS fallback mode (iMessage unavailable).
 */
@Composable
private fun SmsFallbackBanner(
    visible: Boolean,
    fallbackReason: FallbackReason?,
    isServerConnected: Boolean,
    showExitAction: Boolean,
    onExitFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val message = when {
            fallbackReason == FallbackReason.SERVER_DISCONNECTED || !isServerConnected ->
                "Server disconnected - Sending as SMS"
            fallbackReason == FallbackReason.IMESSAGE_FAILED ->
                "iMessage failed - Sending as SMS"
            fallbackReason == FallbackReason.USER_REQUESTED ->
                "Sending as SMS per your request"
            else -> "iMessage unavailable - Sending as SMS"
        }
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.weight(1f, fill = true))
                if (showExitAction) {
                    TextButton(onClick = onExitFallback) {
                        Text("Try iMessage again")
                    }
                }
            }
        }
    }
}

/**
 * Voice memo recording bar that replaces the input area during recording.
 * Shows real-time waveform based on audio amplitude, duration, and cancel/send buttons.
 */
@Composable
private fun VoiceMemoRecordingBar(
    duration: Long,
    amplitudeHistory: List<Float>,
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

    val inputColors = BothBubblesTheme.bubbleColors

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
            // Cancel button (stops recording and enters preview mode)
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Recording indicator with real-time waveform
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

                // Real-time waveform bars based on audio amplitude
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    amplitudeHistory.forEachIndexed { index, amplitude ->
                        // Animate height changes smoothly
                        val targetHeight = (4f + amplitude * 20f).coerceIn(4f, 24f)
                        val animatedHeight by animateFloatAsState(
                            targetValue = targetHeight,
                            animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                            label = "bar_$index"
                        )

                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(animatedHeight.dp)
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
 * Voice memo preview bar that allows playback, re-record, or send.
 * Shown after the user stops recording (X button while recording).
 */
@Composable
private fun VoiceMemoPreviewBar(
    duration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val formattedPosition = remember(playbackPosition) {
        val seconds = (playbackPosition / 1000) % 60
        val minutes = (playbackPosition / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    val progress = if (duration > 0) (playbackPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val inputColors = BothBubblesTheme.bubbleColors

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
            // Cancel button (exits preview mode and deletes recording)
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Play/Pause button
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Progress bar and time
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Progress bar
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Time display
                Text(
                    text = if (isPlaying) formattedPosition else formattedDuration,
                    style = MaterialTheme.typography.bodySmall,
                    color = inputColors.inputText
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Re-record button
            IconButton(onClick = onReRecord) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = "Re-record",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Send button
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

/**
 * Determines if a time separator should be shown between two messages.
 * Shows separator if there's a gap of 15+ minutes between messages.
 */
private fun shouldShowTimeSeparator(currentTimestamp: Long, previousTimestamp: Long): Boolean {
    val gapMillis = currentTimestamp - previousTimestamp
    val gapMinutes = TimeUnit.MILLISECONDS.toMinutes(gapMillis)
    return gapMinutes >= 15
}

/**
 * Formats a timestamp for the centered time separator.
 * Uses relative formatting like "Today 2:30 PM", "Yesterday", "Monday", or full date.
 */
private fun formatTimeSeparator(timestamp: Long): String {
    val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }

    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    return when {
        isSameDay(messageDate, today) -> {
            "Today ${timeFormat.format(Date(timestamp))}"
        }
        isSameDay(messageDate, yesterday) -> {
            "Yesterday ${timeFormat.format(Date(timestamp))}"
        }
        messageDate.after(weekAgo) -> {
            "${dayOfWeekFormat.format(Date(timestamp))} ${timeFormat.format(Date(timestamp))}"
        }
        else -> {
            "${dateFormat.format(Date(timestamp))} ${timeFormat.format(Date(timestamp))}"
        }
    }
}

/**
 * Checks if two Calendar instances represent the same day.
 */
private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

/**
 * Time threshold for grouping consecutive messages (2 minutes).
 * Messages from the same sender within this window will be visually grouped.
 */
private const val GROUP_TIME_THRESHOLD_MS = 2 * 60 * 1000L // 2 minutes

/**
 * Calculates the group position for a message based on adjacent messages.
 * Messages are grouped when they:
 * - Are from the same sender (isFromMe matches)
 * - Are within the time threshold
 * - Are not reaction messages
 *
 * In reversed layout (newest at index 0):
 * - Lower index = newer message (appears lower on screen)
 * - Higher index = older message (appears higher on screen)
 *
 * @return The MessageGroupPosition for visual bubble styling
 */
private fun calculateGroupPosition(
    messages: List<MessageUiModel>,
    index: Int,
    message: MessageUiModel
): MessageGroupPosition {
    // Reaction messages are always single (they're typically hidden anyway)
    if (message.isReaction) {
        return MessageGroupPosition.SINGLE
    }

    // Get adjacent non-reaction messages
    val previousMessage = messages.getOrNull(index - 1)?.takeIf { !it.isReaction }
    val nextMessage = messages.getOrNull(index + 1)?.takeIf { !it.isReaction }

    // Check if this message groups with the message below it (visually)
    // In reversed layout, index - 1 is the newer message that appears below
    val groupsWithBelow = previousMessage?.let { prev ->
        prev.isFromMe == message.isFromMe &&
                kotlin.math.abs(message.dateCreated - prev.dateCreated) <= GROUP_TIME_THRESHOLD_MS
    } ?: false

    // Check if this message groups with the message above it (visually)
    // In reversed layout, index + 1 is the older message that appears above
    val groupsWithAbove = nextMessage?.let { next ->
        next.isFromMe == message.isFromMe &&
                kotlin.math.abs(next.dateCreated - message.dateCreated) <= GROUP_TIME_THRESHOLD_MS
    } ?: false

    return when {
        !groupsWithAbove && !groupsWithBelow -> MessageGroupPosition.SINGLE
        !groupsWithAbove && groupsWithBelow -> MessageGroupPosition.FIRST  // Top of visual group
        groupsWithAbove && groupsWithBelow -> MessageGroupPosition.MIDDLE
        groupsWithAbove && !groupsWithBelow -> MessageGroupPosition.LAST   // Bottom of visual group
        else -> MessageGroupPosition.SINGLE
    }
}

/**
 * Menu shown for failed messages with retry options.
 * Positioned above the message bubble like TapbackMenu.
 */
@Composable
private fun FailedMessageMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRetryAsSms: (() -> Unit)?,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    androidx.compose.ui.window.Popup(
        alignment = if (isFromMe) Alignment.TopEnd else Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, -100),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            clippingEnabled = false
        )
    ) {
        Surface(
            modifier = modifier.padding(4.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Retry button
                Surface(
                    onClick = onRetry,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Retry as SMS button (if available)
                if (onRetryAsSms != null) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Surface(
                        onClick = onRetryAsSms,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = null,
                                tint = Color(0xFF34C759), // SMS green
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Send as SMS",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
