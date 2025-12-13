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
import androidx.compose.ui.geometry.Rect
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
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.util.PhoneNumberFormatter
import com.bothbubbles.R
import com.bothbubbles.services.contacts.VCardService
import com.bothbubbles.services.messaging.FallbackReason
import com.bothbubbles.ui.chat.components.AttachmentPreview
import com.bothbubbles.ui.chat.components.ChatInputArea
import com.bothbubbles.ui.chat.components.EmptyStateMessages
import com.bothbubbles.ui.chat.components.InlineSearchBar
import com.bothbubbles.ui.chat.components.InputMode
import com.bothbubbles.ui.chat.components.LoadingMoreIndicator
import com.bothbubbles.ui.chat.components.QualitySelectionSheet
import com.bothbubbles.ui.chat.components.ReplyPreview
import com.bothbubbles.ui.chat.components.SaveContactBanner
import com.bothbubbles.ui.chat.components.SendingIndicatorBar
import com.bothbubbles.ui.chat.components.SendModeToggleButton
import com.bothbubbles.ui.chat.composer.tutorial.ComposerTutorial
import com.bothbubbles.ui.chat.composer.tutorial.toComposerTutorialState
import com.bothbubbles.ui.chat.components.SmsFallbackBanner
import com.bothbubbles.ui.chat.calculateGroupPosition
import com.bothbubbles.ui.chat.formatTimeSeparator
import com.bothbubbles.ui.chat.shouldShowTimeSeparator
import com.bothbubbles.ui.chat.startVoiceMemoRecording
import com.bothbubbles.ui.components.input.AttachmentPickerPanel
import com.bothbubbles.ui.components.input.EmojiPickerPanel
import com.bothbubbles.ui.components.common.Avatar
import com.bothbubbles.ui.components.common.GroupAvatar
import com.bothbubbles.ui.components.message.DateSeparator
import com.bothbubbles.ui.components.dialogs.ForwardableChatInfo
import com.bothbubbles.ui.components.dialogs.ForwardMessageDialog
import com.bothbubbles.ui.components.message.JumpToBottomIndicator
import com.bothbubbles.ui.components.message.MessageBubble
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.components.input.ScheduleMessageDialog
import com.bothbubbles.ui.components.input.SmartReplyChips
import com.bothbubbles.ui.components.common.SpamSafetyBanner
import com.bothbubbles.ui.components.message.TapbackMenu
import com.bothbubbles.ui.components.message.TypingIndicator
import com.bothbubbles.ui.components.dialogs.VCardOptionsDialog
import com.bothbubbles.ui.components.message.AnimatedThreadOverlay
import com.bothbubbles.ui.components.common.MessageBubbleSkeleton
import com.bothbubbles.ui.components.common.MessageListSkeleton
import com.bothbubbles.ui.components.common.staggeredEntrance
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
    onEditAttachmentClick: (Uri) -> Unit = {},
    capturedPhotoUri: Uri? = null,
    onCapturedPhotoHandled: () -> Unit = {},
    editedAttachmentUri: Uri? = null,
    editedAttachmentCaption: String? = null,
    originalAttachmentUri: Uri? = null,
    onEditedAttachmentHandled: () -> Unit = {},
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

    // Quality selection sheet state
    var showQualitySheet by remember { mutableStateOf(false) }

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
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()

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

    // Send button bounds for tutorial spotlight
    var sendButtonBounds by remember { mutableStateOf(Rect.Zero) }

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
            viewModel.addAttachment(uri)
            onCapturedPhotoHandled()
        }
    }

    // Handle edited attachment
    LaunchedEffect(editedAttachmentUri) {
        if (editedAttachmentUri != null) {
            viewModel.onAttachmentEdited(
                originalUri = originalAttachmentUri ?: editedAttachmentUri,
                editedUri = editedAttachmentUri,
                caption = editedAttachmentCaption
            )
            onEditedAttachmentHandled()
        }
    }

    // Handle shared content from share picker
    LaunchedEffect(sharedText, sharedUris) {
        // Add shared URIs as attachments
        if (sharedUris.isNotEmpty()) {
            sharedUris.forEach { uri ->
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
            ChatTopBar(
                chatTitle = uiState.chatTitle,
                avatarPath = uiState.avatarPath,
                isGroup = uiState.isGroup,
                participantNames = uiState.participantNames,
                participantAvatarPaths = uiState.participantAvatarPaths,
                isSnoozed = uiState.isSnoozed,
                isArchived = uiState.isArchived,
                isStarred = uiState.isStarred,
                showSubjectField = uiState.showSubjectField,
                isLocalSmsChat = uiState.isLocalSmsChat,
                onBackClick = {
                    // Clear saved state when user explicitly navigates back
                    viewModel.onNavigateBack()
                    onBackClick()
                },
                onDetailsClick = onDetailsClick,
                onVideoCallClick = { showVideoCallDialog = true },
                onMenuAction = { action ->
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
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                // Attachment picker panel (slides up above input)
                AttachmentPickerPanel(
                    visible = showAttachmentPicker,
                    onDismiss = { showAttachmentPicker = false },
                    onAttachmentSelected = { uri ->
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
                val composerState by viewModel.composerState.collectAsStateWithLifecycle()

                ChatComposer(
                    state = composerState,
                    onEvent = { event ->
                        when (event) {
                            is ComposerEvent.OpenCamera -> {
                                // TODO: Launch camera
                            }
                            is ComposerEvent.SendLongPress -> {
                                if (!uiState.isLocalSmsChat) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showEffectPicker = true
                                }
                            }
                            is ComposerEvent.OpenQualitySheet -> {
                                showQualitySheet = true
                            }
                            is ComposerEvent.EditAttachment -> {
                                onEditAttachmentClick(event.attachment.uri)
                            }
                            else -> viewModel.onComposerEvent(event)
                        }
                    },
                    onMediaSelected = { uris ->
                        viewModel.addAttachments(uris)
                    },
                    onCameraClick = {
                        // TODO: Launch camera
                    },
                    onFileClick = {
                        // TODO: Launch file picker
                    },
                    onLocationClick = {
                        // TODO: Launch location picker
                    },
                    onContactClick = {
                        // TODO: Launch contact picker
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
                    showAttachmentPicker = false
                    scrollScope.launch { listState.scrollToItem(0) }
                }
            },
            onDismiss = { showEffectPicker = false }
        )
    }

    // Retry bottom sheet for failed messages
    selectedMessageForRetry?.let { failedMessage ->
        RetryMessageBottomSheet(
            messageGuid = failedMessage.guid,
            canRetryAsSms = canRetrySmsForMessage,
            contactIMessageAvailable = uiState.contactIMessageAvailable == true,
            onRetryAsIMessage = {
                viewModel.retryMessage(failedMessage.guid)
                selectedMessageForRetry = null
            },
            onRetryAsSms = {
                viewModel.retryMessageAsSms(failedMessage.guid)
                selectedMessageForRetry = null
            },
            onDismiss = { selectedMessageForRetry = null }
        )
    }

    // Quality Selection Sheet
    QualitySelectionSheet(
        visible = showQualitySheet,
        currentQuality = uiState.attachmentQuality,
        onQualitySelected = { quality ->
            viewModel.setAttachmentQuality(quality)
            showQualitySheet = false
        },
        onDismiss = { showQualitySheet = false }
    )

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
            onConfirm = { options: BlockOptions ->
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
        SmsBlockedDialog(
            onOpenSettings = {
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
            },
            onDismiss = { showSmsBlockedDialog = false }
        )
    }

    if (showVideoCallDialog) {
        VideoCallMethodDialog(
            onGoogleMeet = {
                context.startActivity(viewModel.getGoogleMeetIntent())
                showVideoCallDialog = false
            },
            onWhatsApp = {
                viewModel.getWhatsAppCallIntent()?.let { intent ->
                    context.startActivity(intent)
                }
                showVideoCallDialog = false
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
            viewModel.clearAttachments()

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
    ComposerTutorial(
        tutorialState = uiState.tutorialState.toComposerTutorialState(),
        sendButtonBounds = sendButtonBounds,
        onStepComplete = { step ->
            // The tutorial progresses based on actual swipe gestures, not step completion
            // This callback is for logging/analytics if needed
        },
        onDismiss = {
            viewModel.updateTutorialState(TutorialState.COMPLETED)
        }
    )
}

