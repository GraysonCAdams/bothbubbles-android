package com.bothbubbles.ui.chat

import android.Manifest
import android.content.Intent
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.R
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.ui.chat.components.LoadingMoreIndicator
import com.bothbubbles.ui.chat.components.SendModeToggleButton
import com.bothbubbles.ui.chat.components.SendModeTutorialOverlay
import com.bothbubbles.ui.components.AttachmentPickerPanel
import com.bothbubbles.ui.components.EmojiPickerPanel
import com.bothbubbles.ui.components.Avatar
import com.bothbubbles.ui.components.GroupAvatar
import com.bothbubbles.ui.components.DateSeparator
import com.bothbubbles.ui.components.ForwardableChatInfo
import com.bothbubbles.ui.components.ForwardMessageDialog
import com.bothbubbles.ui.components.JumpToBottomIndicator
import com.bothbubbles.ui.components.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.ScheduleMessageDialog
import com.bothbubbles.ui.components.SmartReplyChips
import com.bothbubbles.ui.components.SpamSafetyBanner
import com.bothbubbles.ui.components.TapbackMenu
import com.bothbubbles.ui.components.TypingIndicator
import com.bothbubbles.ui.components.VCardOptionsDialog
import com.bothbubbles.ui.components.AnimatedThreadOverlay
import com.bothbubbles.ui.components.MessageListSkeleton
import com.bothbubbles.ui.components.MessageBubbleSkeleton
import com.bothbubbles.ui.components.staggeredEntrance
import com.bothbubbles.ui.effects.EffectPickerSheet
import com.bothbubbles.ui.effects.MessageEffect
import com.bothbubbles.ui.effects.bubble.BubbleEffectWrapper
import com.bothbubbles.ui.effects.screen.ScreenEffectOverlay
import com.bothbubbles.ui.theme.BothBubblesTheme
import com.bothbubbles.ui.theme.BubbleColors
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
    initialScrollPosition: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollPositionRestored: () -> Unit = {},
    targetMessageGuid: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val smartReplySuggestions by viewModel.smartReplySuggestions.collectAsStateWithLifecycle()

    // LRU cached scroll position (for instant restore when re-opening recently viewed chat)
    val cachedScrollPosition by viewModel.cachedScrollPosition.collectAsStateWithLifecycle()

    // Determine effective scroll position: navigation state takes priority, then LRU cache
    val effectiveScrollPosition = if (initialScrollPosition > 0 || initialScrollOffset > 0) {
        Pair(initialScrollPosition, initialScrollOffset)
    } else {
        cachedScrollPosition ?: Pair(0, 0)
    }

    // Cache window keeps ~50 messages composed beyond viewport (matching fossify-reference)
    // ahead = prefetch before visible, behind = retain after scrolling past
    @OptIn(ExperimentalFoundationApi::class)
    val cacheWindow = remember { LazyLayoutCacheWindow(ahead = 1000.dp, behind = 2000.dp) }
    @OptIn(ExperimentalFoundationApi::class)
    val listState = rememberLazyListState(
        cacheWindow = cacheWindow,
        initialFirstVisibleItemIndex = effectiveScrollPosition.first,
        initialFirstVisibleItemScrollOffset = effectiveScrollPosition.second
    )

    // Track if scroll position has been restored
    var scrollRestored by remember { mutableStateOf(effectiveScrollPosition.first == 0 && effectiveScrollPosition.second == 0) }

    // Restore scroll position after messages load (if we have state to restore)
    LaunchedEffect(uiState.messages.isNotEmpty(), effectiveScrollPosition) {
        if (!scrollRestored && uiState.messages.isNotEmpty() && (effectiveScrollPosition.first > 0 || effectiveScrollPosition.second > 0)) {
            // Scroll to restored position after messages have loaded
            listState.scrollToItem(effectiveScrollPosition.first, effectiveScrollPosition.second)
            scrollRestored = true
            onScrollPositionRestored()
        }
    }

    // Track if we've handled the target message (from notification deep-link)
    var targetMessageHandled by remember { mutableStateOf(false) }

    // Handle notification deep-link: scroll to target message and highlight it
    // Uses paging-aware jumpToMessage instead of indexOfFirst for sparse loading support
    LaunchedEffect(targetMessageGuid) {
        if (targetMessageGuid != null && !targetMessageHandled) {
            // Use paging-aware jump which loads data if needed
            val position = viewModel.jumpToMessage(targetMessageGuid)
            if (position != null) {
                // Small delay to let data load
                delay(100)
                // Scroll with offset so message isn't at the very top edge
                // In reversed layout, negative offset moves item down (away from visual top)
                listState.animateScrollToItem(position, scrollOffset = -100)
                // Trigger highlight animation after scroll
                viewModel.highlightMessage(targetMessageGuid)
                targetMessageHandled = true
            }
        }
    }
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Hide keyboard when user scrolls more than a threshold
    LaunchedEffect(listState) {
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var accumulatedScroll = 0

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (currentIndex, currentOffset, isScrolling) ->
            if (isScrolling) {
                // Calculate scroll delta
                val scrollDelta = if (currentIndex == previousFirstVisibleItem) {
                    currentOffset - previousScrollOffset
                } else {
                    // Changed items, estimate large scroll
                    (currentIndex - previousFirstVisibleItem) * 200
                }
                accumulatedScroll += kotlin.math.abs(scrollDelta)

                // Hide keyboard after scrolling ~250dp worth
                if (accumulatedScroll > 750) {
                    keyboardController?.hide()
                    accumulatedScroll = 0
                }
            } else {
                // Reset when scroll stops
                accumulatedScroll = 0
            }

            previousFirstVisibleItem = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    // Menu and dialog state
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showVideoCallDialog by remember { mutableStateOf(false) }
    var showSmsBlockedDialog by remember { mutableStateOf(false) }

    // Attachment picker state
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }

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

    // Animation control: only animate new messages after initial load completes
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()

    // Track when fetching older messages from server (for loading indicator at top)
    val isLoadingFromServer by viewModel.isLoadingFromServer.collectAsStateWithLifecycle()

    // Attachment download settings and progress
    val autoDownloadEnabled by viewModel.autoDownloadEnabled.collectAsStateWithLifecycle()
    val downloadingAttachments by viewModel.attachmentDownloadProgress.collectAsStateWithLifecycle()

    // Track processed screen effects this session to avoid re-triggering
    val processedEffectMessages = remember { mutableSetOf<String>() }

    // Track revealed invisible ink messages (resets when leaving chat)
    var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }

    // Tapback menu state
    var selectedMessageForTapback by remember { mutableStateOf<MessageUiModel?>(null) }

    // Track which message is currently being swiped for reply (to hide overlaying stickers)
    var swipingMessageGuid by remember { mutableStateOf<String?>(null) }

    // Thread overlay state
    val threadOverlayState by viewModel.threadOverlayState.collectAsStateWithLifecycle()

    // Handle scroll-to-message events from thread overlay
    // Uses paging-aware jumpToMessage instead of indexOfFirst for sparse loading support
    LaunchedEffect(Unit) {
        viewModel.scrollToGuid.collect { guid ->
            // Use paging-aware jump which loads data if needed
            val position = viewModel.jumpToMessage(guid)
            if (position != null) {
                // Small delay to let data load
                delay(50)
                listState.animateScrollToItem(position)
            }
        }
    }

    // Failed message retry menu state
    var selectedMessageForRetry by remember { mutableStateOf<MessageUiModel?>(null) }
    var canRetrySmsForMessage by remember { mutableStateOf(false) }
    val retryMenuScope = rememberCoroutineScope()
    val scrollScope = rememberCoroutineScope()

    // Snackbar for error display
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when error occurs
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

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
    var isNoiseCancellationEnabled by remember { mutableStateOf(true) }

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
                enableNoiseCancellation = isNoiseCancellationEnabled,
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

    // Track scroll position changes for state restoration, preloading, and paging
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible
            Triple(
                firstVisible,
                listState.firstVisibleItemScrollOffset,
                lastVisible
            )
        }.collect { (index, offset, lastVisibleIndex) ->
            // Note: logging removed to reduce main thread work during scroll
            viewModel.updateScrollPosition(index, offset, lastVisibleIndex - index + 1)
            // Notify paging controller about visible range for sparse loading
            viewModel.onScrollPositionChanged(index, lastVisibleIndex)
        }
    }

    // Cleanup recording and playback on dispose, and notify ViewModel we're leaving
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.release()
            mediaPlayer?.release()
            mediaActionSound.release()
            // Notify ViewModel we're leaving the chat to clear active conversation tracking
            viewModel.onChatLeave()
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        // Clear saved state when user explicitly navigates back
                        viewModel.onNavigateBack()
                        onBackClick()
                    }) {
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
                        if (uiState.isGroup && uiState.participantNames.size > 1) {
                            GroupAvatar(
                                names = uiState.participantNames.ifEmpty { listOf(uiState.chatTitle) },
                                avatarPaths = uiState.participantAvatarPaths,
                                size = 40.dp
                            )
                        } else {
                            Avatar(
                                name = uiState.chatTitle,
                                avatarPath = uiState.avatarPath,
                                size = 40.dp
                            )
                        }
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
                        viewModel.updateDraft(draftText + emoji)
                        showEmojiPicker = false
                    }
                )

                // Determine input mode for unified handling
                val inputMode = when {
                    isRecording -> InputMode.RECORDING
                    isPreviewingVoiceMemo -> InputMode.PREVIEW
                    else -> InputMode.NORMAL
                }

                // Smart reply chips - hide during recording/preview with animation
                AnimatedVisibility(
                    visible = inputMode == InputMode.NORMAL,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SmartReplyChips(
                        suggestions = smartReplySuggestions,
                        onSuggestionClick = { suggestion ->
                            viewModel.updateDraft(suggestion.text)
                            suggestion.templateId?.let { viewModel.recordTemplateUsage(it) }
                        }
                    )
                }

                // Reply preview - shows when replying to a message
                val replyingToGuid = uiState.replyingToGuid
                val replyingToMessage = remember(replyingToGuid, uiState.messages) {
                    replyingToGuid?.let { guid -> uiState.messages.find { it.guid == guid } }
                }

                AnimatedVisibility(
                    visible = replyingToMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    replyingToMessage?.let { message ->
                        ReplyPreview(
                            message = message,
                            onDismiss = { viewModel.clearReply() }
                        )
                    }
                }

                // Unified input area with animated content transitions
                UnifiedInputArea(
                    mode = inputMode,
                    // Normal mode props
                    text = draftText,
                    onTextChange = viewModel::updateDraft,
                    onSendClick = {
                        viewModel.sendMessage()
                        pendingAttachments = emptyList()
                        showAttachmentPicker = false
                        // Scroll to bottom after sending - use instant scroll to avoid jank
                        scrollScope.launch {
                            listState.scrollToItem(0)
                        }
                    },
                    onSendLongPress = {
                        if (!uiState.isLocalSmsChat) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            showEffectPicker = true
                        }
                    },
                    onAttachClick = {
                        showAttachmentPicker = !showAttachmentPicker
                    },
                    onEmojiClick = {
                        showEmojiPicker = !showEmojiPicker
                    },
                    onImageClick = {
                        imagePickerLauncher.launch("image/*")
                    },
                    onVoiceMemoClick = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onVoiceMemoPressStart = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            startVoiceMemoRecording(
                                context = context,
                                enableNoiseCancellation = isNoiseCancellationEnabled,
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
                        if (isRecording) {
                            try {
                                mediaRecorder?.stop()
                                mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                            } catch (_: Exception) { }
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
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
                    isLocalSmsChat = uiState.isLocalSmsChat || uiState.isInSmsFallbackMode,
                    currentSendMode = uiState.currentSendMode,
                    smsInputBlocked = uiState.smsInputBlocked,
                    onSmsInputBlockedClick = { showSmsBlockedDialog = true },
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
                    // Attachment warning props
                    attachmentWarning = uiState.attachmentWarning,
                    onDismissWarning = { viewModel.dismissAttachmentWarning() },
                    onRemoveWarningAttachment = {
                        uiState.attachmentWarning?.affectedUri?.let { uri ->
                            pendingAttachments = pendingAttachments - uri
                            viewModel.removeAttachment(uri)
                        }
                    },
                    // Recording mode props
                    recordingDuration = recordingDuration,
                    amplitudeHistory = amplitudeHistory,
                    onRecordingCancel = {
                        try {
                            mediaRecorder?.stop()
                            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        } catch (_: Exception) { }
                        mediaRecorder?.release()
                        mediaRecorder = null
                        isRecording = false
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
                    onRecordingSend = {
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
                            scrollScope.launch { listState.scrollToItem(0) }
                        }
                        recordingFile = null
                    },
                    isNoiseCancellationEnabled = isNoiseCancellationEnabled,
                    onNoiseCancellationToggle = {
                        isNoiseCancellationEnabled = !isNoiseCancellationEnabled
                    },
                    onRecordingStop = {
                        // Stop recording and go to preview mode
                        try {
                            mediaRecorder?.stop()
                            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        } catch (_: Exception) { }
                        mediaRecorder?.release()
                        mediaRecorder = null
                        isRecording = false
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
                    onRecordingRestart = {
                        // Cancel current recording and start fresh
                        try {
                            mediaRecorder?.stop()
                        } catch (_: Exception) { }
                        mediaRecorder?.release()
                        mediaRecorder = null
                        recordingFile?.delete()
                        recordingFile = null
                        recordingDuration = 0L
                        // Start new recording immediately
                        startVoiceMemoRecording(
                            context = context,
                            enableNoiseCancellation = isNoiseCancellationEnabled,
                            onRecorderCreated = { recorder, file ->
                                mediaRecorder = recorder
                                recordingFile = file
                                isRecording = true
                                mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
                            },
                            onError = { error ->
                                isRecording = false
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    // Preview mode props
                    previewDuration = playbackDuration,
                    playbackPosition = playbackPosition,
                    isPlaying = isPlayingVoiceMemo,
                    onPlayPause = {
                        if (isPlayingVoiceMemo) {
                            mediaPlayer?.pause()
                            isPlayingVoiceMemo = false
                        } else {
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
                        mediaPlayer?.release()
                        mediaPlayer = null
                        isPlayingVoiceMemo = false
                        playbackPosition = 0L
                        recordingFile?.delete()
                        recordingFile = null
                        isPreviewingVoiceMemo = false
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPreviewSend = {
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
                            scrollScope.launch { listState.scrollToItem(0) }
                        }
                        recordingFile = null
                    },
                    onPreviewCancel = {
                        mediaPlayer?.release()
                        mediaPlayer = null
                        isPlayingVoiceMemo = false
                        playbackPosition = 0L
                        isPreviewingVoiceMemo = false
                        recordingFile?.delete()
                        recordingFile = null
                    },
                    // Send mode toggle props
                    canToggleSendMode = uiState.canToggleSendMode,
                    showSendModeRevealAnimation = uiState.showSendModeRevealAnimation,
                    tutorialState = uiState.tutorialState,
                    onModeToggle = { newMode ->
                        val success = viewModel.setSendMode(newMode, persist = true)
                        if (success) {
                            // Progress tutorial if active
                            viewModel.onTutorialToggleSuccess()
                        }
                        success
                    },
                    onRevealAnimationComplete = {
                        viewModel.markRevealAnimationShown()
                    }
                )
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

        // Load more messages when scrolling near the top (older messages)
        // Since reverseLayout=true, higher indices = older messages at the visual top
        LaunchedEffect(listState) {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItem >= totalItems - 25 && totalItems > 0
            }
                .distinctUntilChanged()
                .collect { shouldLoadMore ->
                    if (shouldLoadMore && uiState.canLoadMore && !uiState.isLoadingMore) {
                        viewModel.loadMoreMessages()
                    }
                }
        }

        // Track the previous newest message GUID to detect truly NEW messages (not initial load)
        var previousNewestGuid by remember { mutableStateOf<String?>(null) }
        var hasInitiallyLoaded by remember { mutableStateOf(false) }

        // Track new messages while scrolled away from bottom (for jump-to-bottom indicator)
        var newMessageCountWhileAway by remember { mutableStateOf(0) }

        // Derive whether user is scrolled away from bottom (with reverseLayout=true, index 0 = bottom)
        val isScrolledAwayFromBottom by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 3 }
        }

        // Reset new message count when user scrolls back to bottom
        LaunchedEffect(isScrolledAwayFromBottom) {
            if (!isScrolledAwayFromBottom) {
                newMessageCountWhileAway = 0
            }
        }

        // Auto-scroll to show newest message when it arrives (if user is viewing recent messages)
        // This ensures tall content like link previews isn't clipped by the keyboard
        LaunchedEffect(uiState.messages.firstOrNull()?.guid) {
            val newestGuid = uiState.messages.firstOrNull()?.guid
            val isNearBottom = listState.firstVisibleItemIndex <= 2

            // Skip if no messages yet
            if (newestGuid == null) return@LaunchedEffect

            // Track initial load - don't auto-scroll on first message load
            if (!hasInitiallyLoaded) {
                hasInitiallyLoaded = true
                previousNewestGuid = newestGuid
                return@LaunchedEffect
            }

            // Only auto-scroll if a NEW message arrived (guid changed from previous)
            val isNewMessage = previousNewestGuid != null && previousNewestGuid != newestGuid
            previousNewestGuid = newestGuid

            if (isNewMessage) {
                if (isNearBottom) {
                    // Small delay to let the message render and calculate its height
                    kotlinx.coroutines.delay(100)
                    // Use instant scroll instead of animated to avoid jank during animation
                    listState.scrollToItem(0)
                } else {
                    // User is scrolled away - increment new message counter
                    newMessageCountWhileAway++
                }
            }
        }

        // Auto-scroll when typing indicator appears (if user is within 10% of bottom)
        LaunchedEffect(uiState.isTyping) {
            if (uiState.isTyping) {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                // With reverseLayout=true, index 0 = visual bottom
                // 10% threshold: if within first 10% of items from bottom
                val threshold = (totalItems * 0.1).toInt().coerceAtLeast(1)
                val isNearBottom = listState.firstVisibleItemIndex <= threshold

                if (isNearBottom) {
                    kotlinx.coroutines.delay(100)
                    listState.scrollToItem(0)
                }
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
                isLocalSmsChat = uiState.isLocalSmsChat || uiState.isInSmsFallbackMode,
                hasAttachments = uiState.isSendingWithAttachments,
                progress = uiState.sendProgress,
                pendingMessages = uiState.pendingMessages
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

            // Delayed loading indicator - only show after 500ms to avoid flash
            var showDelayedLoader by remember { mutableStateOf(false) }
            LaunchedEffect(initialLoadComplete) {
                if (!initialLoadComplete) {
                    showDelayedLoader = false
                    delay(500)
                    if (!initialLoadComplete) {
                        showDelayedLoader = true
                    }
                }
            }

            when {
                // Show skeleton only after 500ms delay and only if still loading
                !initialLoadComplete && showDelayedLoader -> {
                    MessageListSkeleton(
                        count = 10,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Show empty state only when we're CERTAIN there are no messages
                initialLoadComplete && uiState.messages.isEmpty() -> {
                    EmptyStateMessages(
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Show blank screen while initial load in progress (before 200ms delay)
                !initialLoadComplete && uiState.messages.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize())
                }
                else -> {
                    // Animate top padding when save contact banner is shown
                    // Extra padding accounts for reaction badges that extend above messages
                    val bannerTopPadding by animateDpAsState(
                        targetValue = if (uiState.showSaveContactBanner) 24.dp else 8.dp,
                        animationSpec = tween(durationMillis = 300),
                        label = "banner_padding"
                    )

                    // PERF: Pre-compute expensive lookups once instead of O(n²) per-item
                    // 1. Map each index to its next visible (non-reaction) message
                    val nextVisibleMessageMap = remember(uiState.messages) {
                        val map = mutableMapOf<Int, MessageUiModel?>()
                        var lastVisibleMessage: MessageUiModel? = null
                        // Iterate backwards to build the "next visible" lookup
                        for (i in uiState.messages.indices.reversed()) {
                            map[i] = lastVisibleMessage
                            if (!uiState.messages[i].isReaction) {
                                lastVisibleMessage = uiState.messages[i]
                            }
                        }
                        map
                    }

                    // 2. Pre-compute the last outgoing message index (first non-reaction from-me message)
                    val lastOutgoingIndex = remember(uiState.messages) {
                        uiState.messages.indexOfFirst { it.isFromMe && !it.isReaction }
                    }

                    // 3. PERF: Pre-compute showSenderName for group chats (O(n) once vs O(1) per-item)
                    // Show when: group chat, incoming message, sender changed from previous (older) message
                    val showSenderNameMap = remember(uiState.messages, uiState.isGroup) {
                        if (!uiState.isGroup) emptyMap()
                        else {
                            val map = mutableMapOf<Int, Boolean>()
                            val messages = uiState.messages
                            for (i in messages.indices) {
                                val message = messages[i]
                                val previousMessage = messages.getOrNull(i + 1)
                                map[i] = !message.isFromMe && message.senderName != null &&
                                    (previousMessage == null || previousMessage.isFromMe ||
                                        previousMessage.senderName != message.senderName)
                            }
                            map
                        }
                    }

                    // 4. PERF: Pre-compute showAvatar for group chats (O(n) once vs O(1) per-item)
                    // Show on the last (newest) message in a consecutive group from same sender
                    val showAvatarMap = remember(uiState.messages, uiState.isGroup) {
                        if (!uiState.isGroup) emptyMap()
                        else {
                            val map = mutableMapOf<Int, Boolean>()
                            val messages = uiState.messages
                            for (i in messages.indices) {
                                val message = messages[i]
                                val newerMessage = messages.getOrNull(i - 1)
                                map[i] = !message.isFromMe &&
                                    (newerMessage == null || newerMessage.isFromMe ||
                                        newerMessage.senderName != message.senderName)
                            }
                            map
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = bannerTopPadding,
                                bottom = 8.dp
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

                        // Typing indicator - shows when someone is typing
                        // Since reverseLayout=true, adding at start puts it at visual bottom
                        if (uiState.isTyping) {
                            item(key = "typing_indicator") {
                                TypingIndicator(
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }

                        itemsIndexed(
                            items = uiState.messages,
                            key = { _, message -> message.guid },
                            contentType = { _, message -> if (message.isFromMe) 1 else 0 }
                        ) { index, message ->
                            // Enable tapback for server-origin messages with content
                            // Server-origin = IMESSAGE or SERVER_SMS (from BlueBubbles server)
                            // Local SMS/MMS cannot have tapbacks
                            // Tapbacks require server connection and private API
                            val canTapback = !message.text.isNullOrBlank() &&
                                message.isServerOrigin &&
                                uiState.isServerConnected &&
                                !message.guid.startsWith("temp") &&
                                !message.guid.startsWith("error") &&
                                !message.hasError

                            // Check if this message is a search match or the current match
                            val isSearchMatch = uiState.isSearchActive && index in uiState.searchMatchIndices
                            val isCurrentSearchMatch = uiState.isSearchActive &&
                                uiState.currentSearchMatchIndex >= 0 &&
                                uiState.searchMatchIndices.getOrNull(uiState.currentSearchMatchIndex) == index

                            // Check for time gap with next visible message (previous in chronological order)
                            // Since list is reversed, next index = earlier message
                            // Skip reaction messages (they're hidden) when finding next message
                            // Also don't show separators for reaction messages themselves
                            // PERF: Use pre-computed map instead of O(n) drop().firstOrNull() per item
                            val nextVisibleMessage = nextVisibleMessageMap[index]
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

                            // iPhone-style delivery indicator: only show on THE last outgoing message
                            // in the entire conversation (not just last in consecutive sequence)
                            // Don't show indicator while message is still sending (no clock icon)
                            // PERF: Use pre-computed lastOutgoingIndex instead of O(n) search per item
                            val showDeliveryIndicator = message.isFromMe &&
                                index == lastOutgoingIndex &&
                                (message.isSent || message.hasError)

                            // Spacing based on group position:
                            // - SINGLE/FIRST: 6dp top (gap between groups)
                            // - MIDDLE/LAST: 2dp top (tight within group)
                            // - Placed stickers use offset instead of padding for overlap effect
                            val topPadding = when {
                                message.isPlacedSticker -> 0.dp  // Stickers use offset for overlap
                                groupPosition == MessageGroupPosition.SINGLE || groupPosition == MessageGroupPosition.FIRST -> 6.dp
                                else -> 2.dp
                            }
                            // Negative offset to move sticker UP to overlap the message it's placed on
                            // (In reversed layout, sticker appears below/after its target message)
                            val stickerOverlapOffset = if (message.isPlacedSticker) (-20).dp else 0.dp

                            // PERF: Use pre-computed maps for O(1) lookup instead of runtime calculation
                            val showSenderName = showSenderNameMap[index] ?: false
                            val showAvatar = showAvatarMap[index] ?: false

                            // Fade out/hide stickers when the underlying message is being interacted with
                            // Note: associatedMessageGuid may have "p:X/" prefix (e.g., "p:0/MESSAGE_GUID")
                            val targetGuid = message.associatedMessageGuid?.let { guid ->
                                if (guid.contains("/")) guid.substringAfter("/") else guid
                            }
                            val isStickerTargetInteracting = message.isPlacedSticker && (
                                selectedMessageForTapback?.guid == targetGuid ||
                                swipingMessageGuid == targetGuid
                            )
                            val stickerFadeAlpha = if (isStickerTargetInteracting) 0f else 1f  // Hide completely during interaction

                            // Check if this message should be highlighted (from notification deep-link)
                            val isHighlighted = uiState.highlightedMessageGuid == message.guid

                            // iOS-like highlight animation: amber/gold glow that pulses and fades
                            val highlightAlpha = remember { Animatable(0f) }
                            LaunchedEffect(isHighlighted) {
                                if (isHighlighted) {
                                    // Phase 1: Fade in
                                    highlightAlpha.animateTo(0.3f, tween(200))
                                    // Phase 2: Pulse 3 times
                                    repeat(3) {
                                        highlightAlpha.animateTo(0.15f, tween(200))
                                        highlightAlpha.animateTo(0.3f, tween(200))
                                    }
                                    // Phase 3: Fade out
                                    highlightAlpha.animateTo(0f, tween(400))
                                    // Clear highlight state after animation completes
                                    viewModel.clearHighlight()
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .zIndex(if (message.isPlacedSticker) 1f else 0f)  // Stickers render on top
                                    .alpha(stickerFadeAlpha)
                                    .offset(y = stickerOverlapOffset)
                                    .padding(top = topPadding)
                                    // Messages appear instantly in groups as they're fetched
                                    // No entrance animation - only new incoming messages via socket should animate
                                    .staggeredEntrance(index = index, enabled = false)
                                    // PERF: Use snap() instead of spring animations to reduce frame drops
                                    // during rapid scrolling and message updates (reactions, delivery status)
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = snap()
                                    )
                                    .then(
                                        if (highlightAlpha.value > 0f) {
                                            Modifier.background(
                                                color = Color(0xFFFFD54F).copy(alpha = highlightAlpha.value),
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                        } else Modifier
                                    )
                            ) {
                                // Show centered time separator BEFORE the message
                                // In reversed layout, this makes it appear above the message visually
                                if (showTimeSeparator) {
                                    DateSeparator(
                                        date = formatTimeSeparator(message.dateCreated)
                                    )
                                }

                                // Show sender name for group chat incoming messages
                                // Add extra padding to align with message bubble (after avatar space)
                                if (showSenderName) {
                                    Text(
                                        text = message.senderName!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 52.dp, bottom = 2.dp) // 28dp avatar + 8dp gap + 16dp bubble padding
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

                                    // Check if message has media attachments (for invisible ink behavior)
                                    val hasMedia = remember(message.attachments) {
                                        message.attachments.any { it.isImage || it.isVideo }
                                    }

                                    // Track invisible ink reveal state locally (resets on chat exit)
                                    val isInvisibleInkRevealed = message.guid in revealedInvisibleInkMessages
                                    val isInvisibleInk = bubbleEffect == MessageEffect.Bubble.InvisibleInk

                                    BubbleEffectWrapper(
                                        effect = bubbleEffect,
                                        isNewMessage = shouldAnimateBubble,
                                        isFromMe = message.isFromMe,
                                        onEffectComplete = { viewModel.onBubbleEffectCompleted(message.guid) },
                                        isInvisibleInkRevealed = isInvisibleInkRevealed,
                                        onInvisibleInkRevealChanged = { revealed ->
                                            // Update local revealed state
                                            revealedInvisibleInkMessages = if (revealed) {
                                                revealedInvisibleInkMessages + message.guid
                                            } else {
                                                revealedInvisibleInkMessages - message.guid
                                            }
                                        },
                                        hasMedia = hasMedia,
                                        onMediaClickBlocked = {
                                            // Optional: show feedback when user tries to tap blocked media
                                        }
                                    ) {
                                        MessageBubble(
                                            message = message,
                                            onLongPress = {
                                                // Don't allow reactions/replies on stickers placed on other messages
                                                if (message.isPlacedSticker) return@MessageBubble

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
                                            // Block media click if invisible ink not revealed
                                            onMediaClick = if (isInvisibleInk && hasMedia && !isInvisibleInkRevealed) { _ -> } else onMediaClick,
                                            groupPosition = groupPosition,
                                            searchQuery = if (uiState.isSearchActive) uiState.searchQuery else null,
                                            isCurrentSearchMatch = isCurrentSearchMatch,
                                            // Manual download mode: pass callback when auto-download is disabled
                                            onDownloadClick = if (!autoDownloadEnabled) {
                                                { attachmentGuid -> viewModel.downloadAttachment(attachmentGuid) }
                                            } else null,
                                            downloadingAttachments = downloadingAttachments,
                                            showDeliveryIndicator = showDeliveryIndicator,
                                            // Don't allow reply on stickers placed on other messages
                                            onReply = if (message.isPlacedSticker) null else { guid -> viewModel.setReplyTo(guid) },
                                            onReplyIndicatorClick = { originGuid -> viewModel.loadThread(originGuid) },
                                            // Track when this message is being swiped (to hide overlaying stickers)
                                            onSwipeStateChanged = { isSwiping ->
                                                swipingMessageGuid = if (isSwiping) message.guid else null
                                            },
                                            // Retry button callback - shows the retry menu
                                            onRetry = { guid ->
                                                selectedMessageForRetry = message
                                                retryMenuScope.launch {
                                                    canRetrySmsForMessage = viewModel.canRetryAsSms(guid)
                                                }
                                            },
                                            isGroupChat = uiState.isGroup,
                                            showAvatar = showAvatar
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

                                    // Retry bottom sheet is shown at Scaffold level, not per-message
                                }
                            }
                        }

                        // Loading more indicator - shows skeleton bubbles at top when loading older messages
                        // Since reverseLayout=true, adding at end puts it at visual top
                        if (uiState.isLoadingMore) {
                            item(key = "loading_more") {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MessageBubbleSkeleton(isFromMe = false)
                                    MessageBubbleSkeleton(isFromMe = true)
                                }
                            }
                        }

                        // Loading indicator - shows when fetching older messages from server
                        // Since reverseLayout=true, adding at end puts it at visual top
                        // Acts as soft scroll boundary while fetch is in progress
                        if (isLoadingFromServer && uiState.messages.isNotEmpty()) {
                            item(key = "loading_more_indicator") {
                                LoadingMoreIndicator()
                            }
                        }

                        // Syncing indicator - shows skeleton bubbles at top while fetching messages
                        // Since reverseLayout=true, adding at end puts it at visual top
                        if (uiState.isSyncingMessages && uiState.messages.isNotEmpty()) {
                            item(key = "sync_skeleton") {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MessageBubbleSkeleton(isFromMe = false)
                                    MessageBubbleSkeleton(isFromMe = true)
                                    MessageBubbleSkeleton(isFromMe = false)
                                }
                            }
                        }
                        }

                        // Jump to bottom / new messages indicator
                        JumpToBottomIndicator(
                            visible = isScrolledAwayFromBottom,
                            newMessageCount = newMessageCountWhileAway,
                            onClick = {
                                scrollScope.launch {
                                    listState.animateScrollToItem(0)
                                    newMessageCountWhileAway = 0
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        )
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

    // Thread overlay - shows when user taps a reply indicator
    AnimatedThreadOverlay(
        threadChain = threadOverlayState,
        onMessageClick = { guid -> viewModel.scrollToMessage(guid) },
        onDismiss = { viewModel.dismissThreadOverlay() }
    )
    } // End of outer Box

    // Effect picker bottom sheet
    if (showEffectPicker) {
        EffectPickerSheet(
            messageText = draftText,
            onEffectSelected = { effect ->
                showEffectPicker = false
                if (effect != null) {
                    // Send message with effect
                    // Screen effect will trigger automatically when the message appears in the list
                    viewModel.sendMessage(effect.appleId)
                    pendingAttachments = emptyList()
                    showAttachmentPicker = false
                    scrollScope.launch { listState.scrollToItem(0) }
                }
            },
            onDismiss = { showEffectPicker = false }
        )
    }

    // Retry bottom sheet for failed messages
    selectedMessageForRetry?.let { failedMessage ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForRetry = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Text(
                    text = "Message Not Delivered",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Retry as iMessage option (if contact supports it)
                if (uiState.contactIMessageAvailable == true) {
                    Surface(
                        onClick = {
                            viewModel.retryMessage(failedMessage.guid)
                            selectedMessageForRetry = null
                        },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                color = Color(0xFF007AFF).copy(alpha = 0.1f),
                                shape = CircleShape,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color(0xFF007AFF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Try Again as iMessage",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Send via BlueBubbles server",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Retry as SMS option
                if (canRetrySmsForMessage) {
                    Surface(
                        onClick = {
                            viewModel.retryMessageAsSms(failedMessage.guid)
                            selectedMessageForRetry = null
                        },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                color = Color(0xFF34C759).copy(alpha = 0.1f),
                                shape = CircleShape,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Sms,
                                        contentDescription = null,
                                        tint = Color(0xFF34C759),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Send as Text Message",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Send via your phone's SMS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Cancel option
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Surface(
                    onClick = { selectedMessageForRetry = null },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
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

    if (showSmsBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showSmsBlockedDialog = false },
            title = { Text("Cannot Send SMS") },
            text = {
                Text("BothBubbles must be set as the default SMS app to send SMS messages.\n\nGo to Settings → Apps → Default apps → SMS app and select BothBubbles.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSmsBlockedDialog = false
                    // Open default apps settings
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback to general settings
                        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmsBlockedDialog = false }) {
                    Text("Cancel")
                }
            }
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
                text = draftText,
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

    // Tutorial overlay - full screen overlay on top of everything
    SendModeTutorialOverlay(
        tutorialState = uiState.tutorialState,
        onTutorialProgress = { newState ->
            viewModel.updateTutorialState(newState)
        }
    )
}

/**
 * Input mode for the unified input area
 */
private enum class InputMode {
    NORMAL,     // Standard text input
    RECORDING,  // Voice memo recording in progress
    PREVIEW     // Voice memo preview/playback
}

/**
 * Unified input area that handles all three input modes (normal, recording, preview)
 * with smooth animated transitions and consistent dimensions.
 */
@Composable
private fun UnifiedInputArea(
    mode: InputMode,
    // Normal mode props
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onSendLongPress: () -> Unit,
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onImageClick: () -> Unit,
    onVoiceMemoClick: () -> Unit,
    onVoiceMemoPressStart: () -> Unit,
    onVoiceMemoPressEnd: () -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    currentSendMode: ChatSendMode,
    smsInputBlocked: Boolean,
    onSmsInputBlockedClick: () -> Unit,
    hasAttachments: Boolean,
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
    onClearAllAttachments: () -> Unit,
    isPickerExpanded: Boolean,
    // Attachment warning props
    attachmentWarning: AttachmentWarning? = null,
    onDismissWarning: () -> Unit = {},
    onRemoveWarningAttachment: () -> Unit = {},
    // Recording mode props
    recordingDuration: Long,
    amplitudeHistory: List<Float>,
    onRecordingCancel: () -> Unit,
    onRecordingSend: () -> Unit,
    isNoiseCancellationEnabled: Boolean = true,
    onNoiseCancellationToggle: () -> Unit = {},
    onRecordingStop: () -> Unit = {},
    onRecordingRestart: () -> Unit = {},
    // Preview mode props
    previewDuration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    onPreviewSend: () -> Unit,
    onPreviewCancel: () -> Unit,
    // Send mode toggle props
    canToggleSendMode: Boolean = false,
    showSendModeRevealAnimation: Boolean = false,
    tutorialState: TutorialState = TutorialState.NOT_SHOWN,
    onModeToggle: (ChatSendMode) -> Boolean = { false },
    onRevealAnimationComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // MMS mode is for local SMS chats when attachments or long text is present
    val isMmsMode = isLocalSmsChat && (hasAttachments || text.length > 160)
    val hasContent = text.isNotBlank() || hasAttachments
    // Use currentSendMode for UI coloring (SMS = green, iMessage = blue)
    val isSmsMode = currentSendMode == ChatSendMode.SMS
    val inputColors = BothBubblesTheme.bubbleColors
    val bubbleColors = BothBubblesTheme.bubbleColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            // Attachment previews (only in normal mode)
            AnimatedVisibility(
                visible = mode == InputMode.NORMAL && attachments.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
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

            // Attachment size warning banner
            AnimatedVisibility(
                visible = attachmentWarning != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                attachmentWarning?.let { warning ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (warning.isError)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.tertiaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (warning.isError)
                                        Icons.Default.ErrorOutline
                                    else
                                        Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (warning.isError)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = warning.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (warning.isError)
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (warning.isError && warning.affectedUri != null) {
                                    TextButton(
                                        onClick = onRemoveWarningAttachment,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = "Remove",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                } else if (!warning.isError) {
                                    TextButton(
                                        onClick = onDismissWarning,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = "Dismiss",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Main input row with fixed structure
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side cancel button - only visible in preview mode (recording has controls in panel)
                AnimatedVisibility(
                    visible = mode == InputMode.PREVIEW,
                    enter = fadeIn(animationSpec = tween(150)) +
                        slideInHorizontally(animationSpec = tween(200)) { -it / 2 },
                    exit = fadeOut(animationSpec = tween(150)) +
                        slideOutHorizontally(animationSpec = tween(200)) { -it / 2 }
                ) {
                    Row {
                        // Cancel button for preview mode
                        Surface(
                            onClick = onPreviewCancel,
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                // Center content - animated between text field / recording / preview
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200)) +
                            slideInHorizontally(animationSpec = tween(250)) { it / 3 })
                            .togetherWith(fadeOut(animationSpec = tween(150)) +
                                slideOutHorizontally(animationSpec = tween(200)) { -it / 3 })
                    },
                    modifier = Modifier.weight(1f),
                    label = "center_content"
                ) { currentMode ->
                    when (currentMode) {
                        InputMode.NORMAL -> {
                            // Normal text input field
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 36.dp),
                                shape = RoundedCornerShape(18.dp),
                                color = inputColors.inputFieldBackground
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 6.dp, end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Add button - solid circle that rotates to X when drawer open
                                    val addButtonRotation by animateFloatAsState(
                                        targetValue = if (isPickerExpanded) 45f else 0f,
                                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                                        label = "addButtonRotation"
                                    )
                                    val addButtonColor by animateColorAsState(
                                        targetValue = if (isPickerExpanded) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            inputColors.inputIcon.copy(alpha = 0.7f)
                                        },
                                        animationSpec = tween(200),
                                        label = "addButtonColor"
                                    )
                                    Surface(
                                        onClick = onAttachClick,
                                        modifier = Modifier.size(28.dp),
                                        shape = CircleShape,
                                        color = addButtonColor
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = stringResource(R.string.attach_file),
                                                tint = if (isPickerExpanded) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    inputColors.inputFieldBackground
                                                },
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .graphicsLayer { rotationZ = addButtonRotation }
                                            )
                                        }
                                    }

                                    TextField(
                                        value = text,
                                        onValueChange = { if (!smsInputBlocked) onTextChange(it) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(
                                                if (smsInputBlocked) {
                                                    Modifier.clickable { onSmsInputBlockedClick() }
                                                } else Modifier
                                            ),
                                        enabled = !smsInputBlocked,
                                        readOnly = smsInputBlocked,
                                        placeholder = {
                                            Text(
                                                text = if (smsInputBlocked) {
                                                    "Not default SMS app"
                                                } else {
                                                    stringResource(
                                                        // Use currentSendMode for placeholder text
                                                        if (isSmsMode) R.string.message_placeholder_text
                                                        else R.string.message_placeholder_imessage
                                                    )
                                                },
                                                color = inputColors.inputPlaceholder.copy(
                                                    alpha = if (smsInputBlocked) 0.5f else 1f
                                                )
                                            )
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            disabledIndicatorColor = Color.Transparent,
                                            focusedTextColor = inputColors.inputText,
                                            unfocusedTextColor = inputColors.inputText,
                                            disabledTextColor = inputColors.inputText.copy(alpha = 0.5f),
                                            cursorColor = MaterialTheme.colorScheme.primary
                                        ),
                                        maxLines = 4
                                    )

                                    // Emoji icon button
                                    IconButton(
                                        onClick = onEmojiClick,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.EmojiEmotions,
                                            contentDescription = stringResource(R.string.emoji),
                                            tint = inputColors.inputIcon,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Image/Gallery icon button
                                    IconButton(
                                        onClick = onImageClick,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = stringResource(R.string.image),
                                            tint = inputColors.inputIcon,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                        InputMode.RECORDING -> {
                            // Expanded recording panel with controls
                            ExpandedRecordingPanel(
                                duration = recordingDuration,
                                amplitudeHistory = amplitudeHistory,
                                isNoiseCancellationEnabled = isNoiseCancellationEnabled,
                                onNoiseCancellationToggle = onNoiseCancellationToggle,
                                onStop = onRecordingStop,
                                onRestart = onRecordingRestart,
                                onAttach = onRecordingSend,
                                inputColors = inputColors
                            )
                        }
                        InputMode.PREVIEW -> {
                            // Preview/playback controls
                            PreviewContent(
                                duration = previewDuration,
                                playbackPosition = playbackPosition,
                                isPlaying = isPlaying,
                                onPlayPause = onPlayPause,
                                onReRecord = onReRecord,
                                inputColors = inputColors
                            )
                        }
                    }
                }

                // Hide right side buttons during RECORDING mode (controls are in the expanded panel)
                AnimatedVisibility(
                    visible = mode != InputMode.RECORDING,
                    enter = fadeIn(animationSpec = tween(150)) +
                        slideInHorizontally(animationSpec = tween(200)) { it / 2 },
                    exit = fadeOut(animationSpec = tween(150)) +
                        slideOutHorizontally(animationSpec = tween(200)) { it / 2 }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(6.dp))

                        // Right side action button - voice memo or send
                        Crossfade(
                            targetState = when {
                                tutorialState == TutorialState.STEP_1_SWIPE_UP ||
                                        tutorialState == TutorialState.STEP_2_SWIPE_BACK -> true
                                mode == InputMode.PREVIEW -> true
                                hasContent -> true
                                else -> false
                            },
                            label = "action_button"
                        ) { showSend ->
                            if (showSend) {
                                // Use toggle button in normal mode when toggle is available
                                if (mode == InputMode.NORMAL && canToggleSendMode) {
                                    SendModeToggleButton(
                                        onClick = onSendClick,
                                        onLongPress = onSendLongPress,
                                        currentMode = currentSendMode,
                                        canToggle = canToggleSendMode,
                                        onModeToggle = onModeToggle,
                                        isSending = isSending,
                                        isMmsMode = isMmsMode,
                                        showRevealAnimation = showSendModeRevealAnimation,
                                        tutorialActive = tutorialState == TutorialState.STEP_1_SWIPE_UP ||
                                                tutorialState == TutorialState.STEP_2_SWIPE_BACK,
                                        onAnimationConfigChange = { config ->
                                            // Mark animation as complete when it finishes
                                            if (config.phase == SendButtonAnimationPhase.IDLE) {
                                                onRevealAnimationComplete()
                                            }
                                        }
                                    )
                                } else {
                                    // Fall back to regular send button for preview or when toggle not available
                                    SendButton(
                                        onClick = when (mode) {
                                            InputMode.PREVIEW -> onPreviewSend
                                            else -> onSendClick
                                        },
                                        onLongPress = if (mode == InputMode.NORMAL) onSendLongPress else { {} },
                                        isSending = isSending && mode == InputMode.NORMAL,
                                        isSmsMode = isSmsMode,
                                        isMmsMode = isMmsMode && mode == InputMode.NORMAL,
                                        showEffectHint = !isSmsMode && mode == InputMode.NORMAL
                                    )
                                }
                            } else {
                                VoiceMemoButton(
                                    onClick = onVoiceMemoClick,
                                    onPressStart = onVoiceMemoPressStart,
                                    onPressEnd = onVoiceMemoPressEnd,
                                    isSmsMode = isSmsMode,
                                    isDisabled = smsInputBlocked
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Recording mode center content with pulsing indicator and waveform
 */
@Composable
private fun ExpandedRecordingPanel(
    duration: Long,
    amplitudeHistory: List<Float>,
    isNoiseCancellationEnabled: Boolean,
    onNoiseCancellationToggle: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onAttach: () -> Unit,
    inputColors: BubbleColors,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val formattedDuration = remember(duration) {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000) / 60
        String.format("%d:%02d", minutes, seconds)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timer row with pulsing dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Pulsing red recording dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = Color.Red.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.headlineMedium,
                    color = inputColors.inputText
                )
            }

            // Waveform visualization - larger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                amplitudeHistory.forEachIndexed { index, amplitude ->
                    val targetHeight = (8f + amplitude * 48f).coerceIn(8f, 56f)
                    val animatedHeight by animateFloatAsState(
                        targetValue = targetHeight,
                        animationSpec = spring(
                            dampingRatio = 0.6f,
                            stiffness = 400f
                        ),
                        label = "bar_$index"
                    )

                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(animatedHeight.dp)
                            .background(
                                color = Color.Red.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            // Noise cancellation toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = inputColors.inputText.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.noise_cancellation) + " " +
                           if (isNoiseCancellationEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.bodySmall,
                    color = inputColors.inputText.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isNoiseCancellationEnabled,
                    onCheckedChange = { onNoiseCancellationToggle() },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // Bottom controls row: Restart, Stop, Attach
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Restart button
                TextButton(onClick = onRestart) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = stringResource(R.string.restart_recording),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.restart_recording))
                }

                // Red stop button (prominent)
                Surface(
                    onClick = onStop,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = Color.Red
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Stop square icon
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    }
                }

                // Attach button (pill shape with checkmark)
                Surface(
                    onClick = onAttach,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.attach_voice_memo),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Preview mode center content with playback controls
 */
@Composable
private fun PreviewContent(
    duration: Long,
    playbackPosition: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onReRecord: () -> Unit,
    inputColors: BubbleColors,
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = inputColors.inputFieldBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

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

            Spacer(modifier = Modifier.width(8.dp))

            // Time display
            Text(
                text = if (isPlaying) formattedPosition else formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = inputColors.inputText,
                modifier = Modifier.width(36.dp)
            )

            // Re-record button
            IconButton(
                onClick = onReRecord,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = "Re-record",
                    tint = inputColors.inputIcon,
                    modifier = Modifier.size(20.dp)
                )
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
    // Protocol-based coloring: green for SMS, deep blue for iMessage (matching bubbles)
    // Animated color transition for snappy mode switching (Android 16 style)
    val bubbleColors = BothBubblesTheme.bubbleColors
    val containerColor by animateColorAsState(
        targetValue = if (isSmsMode) Color(0xFF34C759) else bubbleColors.iMessageSent,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "sendButtonColor"
    )
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
            .fillMaxHeight()
            .aspectRatio(1f)
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
                modifier = Modifier.size(18.dp),
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
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "MMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        fontSize = 8.sp
                    )
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
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
    isDisabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Protocol-based coloring: green for SMS, deep blue for iMessage (matching bubbles)
    // Animated color transition for snappy mode switching (Android 16 style)
    val bubbleColors = BothBubblesTheme.bubbleColors
    val containerColor by animateColorAsState(
        targetValue = when {
            isDisabled -> Color.Gray.copy(alpha = 0.3f)
            isSmsMode -> Color(0xFF34C759)
            else -> bubbleColors.iMessageSent
        },
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "voiceMemoButtonColor"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .then(
                if (isDisabled) Modifier else Modifier.pointerInput(Unit) {
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
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(R.string.voice_memo),
            modifier = Modifier.size(20.dp),
            tint = if (isDisabled) Color.White.copy(alpha = 0.4f) else Color.White
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
 * Thin progress bar that appears at the top when sending a message.
 * Shows immediately at 10% when send starts, then shows real upload progress for remaining 90%.
 * Color-coded based on message protocol (green for SMS, blue for iMessage).
 */
@Composable
private fun SendingIndicatorBar(
    isVisible: Boolean,
    isLocalSmsChat: Boolean,
    hasAttachments: Boolean,
    progress: Float = 0f,
    pendingMessages: List<PendingMessage> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Determine color from first pending message, fallback to chat type
    val isSmsSend = pendingMessages.firstOrNull()?.isLocalSms ?: isLocalSmsChat
    val progressColor = if (isSmsSend) {
        Color(0xFF34C759) // Green for SMS
    } else {
        MaterialTheme.colorScheme.primary // Blue for iMessage
    }
    val trackColor = progressColor.copy(alpha = 0.3f)

    // Track completion state for smooth fade-out
    var completingProgress by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(0L) }

    // Animated progress for smooth transitions
    val animatedProgress by animateFloatAsState(
        targetValue = when {
            completingProgress -> 1f
            isVisible -> progress.coerceAtLeast(0.1f) // Always show at least 10%
            else -> 0f
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sendProgress"
    )

    // Handle visibility changes
    LaunchedEffect(isVisible) {
        if (isVisible) {
            startTime = System.currentTimeMillis()
            completingProgress = false
        } else if (startTime > 0) {
            // Send completed - ensure minimum visible duration then animate to 100%
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 400) {
                delay(400 - elapsed)
            }
            completingProgress = true
            delay(400) // Hold at 100% briefly before hiding
            completingProgress = false
            startTime = 0L
        }
    }

    // Determine if bar should be visible - show immediately when sending
    val shouldShow = isVisible || completingProgress

    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(200))
    ) {
        LinearProgressIndicator(
            progress = { animatedProgress.coerceIn(0f, 1f) },
            modifier = modifier
                .fillMaxWidth()
                .height(3.dp),
            color = progressColor,
            trackColor = trackColor
        )
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
 * Thin, non-obtrusive banner shown when chat is in SMS fallback mode.
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
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "iMessage unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Starts voice memo recording using MediaRecorder.
 * Creates a temporary file and configures the recorder for audio capture.
 */
private fun startVoiceMemoRecording(
    context: android.content.Context,
    enableNoiseCancellation: Boolean = true,
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
            // Use VOICE_RECOGNITION for built-in noise suppression when enabled
            setAudioSource(
                if (enableNoiseCancellation) MediaRecorder.AudioSource.VOICE_RECOGNITION
                else MediaRecorder.AudioSource.MIC
            )
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
 * Reply preview shown above the input when replying to a message.
 */
@Composable
private fun ReplyPreview(
    message: MessageUiModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Replying to ${if (message.isFromMe) "yourself" else message.senderName ?: "message"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = message.text ?: "[Attachment]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
