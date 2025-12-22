package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.ui.chat.composer.tutorial.ComposerTutorial
import com.bothbubbles.ui.chat.composer.tutorial.toComposerTutorialState
import com.bothbubbles.ui.chat.components.QualitySelectionSheet
import com.bothbubbles.ui.chat.components.SearchResultsSheet
import com.bothbubbles.ui.chat.delegates.ChatConnectionDelegate
import com.bothbubbles.ui.chat.delegates.ChatOperationsDelegate
import com.bothbubbles.ui.chat.delegates.ChatSearchDelegate
import com.bothbubbles.ui.chat.delegates.ChatSendDelegate
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.components.dialogs.DiscordChannelHelpOverlay
import com.bothbubbles.ui.components.dialogs.DiscordChannelSetupDialog
import com.bothbubbles.ui.components.dialogs.ForwardableChatInfo
import com.bothbubbles.ui.components.dialogs.ForwardMessageDialog
import com.bothbubbles.ui.components.dialogs.VCardOptionsDialog
import com.bothbubbles.ui.components.input.ScheduleMessageDialog
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.effects.EffectPickerSheet
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.flow.flowOf

/**
 * Hosts all dialogs and bottom sheets for the ChatScreen.
 * Extracted to reduce ChatScreen complexity and improve maintainability.
 *
 * PERF FIX: This component now collects connection state internally from the delegate
 * to avoid ChatScreen recomposition when connection state changes. Additionally,
 * forwardableChats is only collected when the forward dialog is shown, and
 * isWhatsAppAvailable is checked via LaunchedEffect to avoid blocking composition.
 *
 * This component manages visibility and callbacks for:
 * - EffectPickerSheet
 * - RetryMessageBottomSheet
 * - QualitySelectionSheet
 * - SearchResultsSheet
 * - DeleteConversationDialog
 * - BlockAndReportDialog
 * - SmsBlockedDialog
 * - VideoCallMethodDialog
 * - DiscordChannelSetupDialog
 * - DiscordChannelHelpOverlay
 * - ScheduleMessageDialog
 * - VCardOptionsDialog
 * - ForwardMessageDialog
 * - ComposerTutorial
 *
 * @param isBubbleMode When true, most dialogs are skipped (only RetryMessageBottomSheet is kept)
 */
@Composable
fun ChatDialogsHost(
    // ViewModel for operations
    viewModel: ChatViewModel,
    context: Context,

    // Wave 2: Delegates for internal collection
    connectionDelegate: ChatConnectionDelegate,
    sendDelegate: ChatSendDelegate,
    operationsDelegate: ChatOperationsDelegate,
    searchDelegate: ChatSearchDelegate,

    // State objects (still needed at this level)
    chatInfoState: ChatInfoState,

    // Dialog visibility states
    showEffectPicker: Boolean,
    showDeleteDialog: Boolean,
    showBlockDialog: Boolean,
    showSmsBlockedDialog: Boolean,
    showVideoCallDialog: Boolean,
    showDiscordSetupDialog: Boolean,
    showDiscordHelpOverlay: Boolean,
    showScheduleDialog: Boolean,
    showVCardOptionsDialog: Boolean,
    showQualitySheet: Boolean,
    showForwardDialog: Boolean,

    // Data for dialogs
    selectedMessageForRetry: MessageUiModel?,
    canRetrySmsForMessage: Boolean,
    messageToForward: MessageUiModel?,
    messagesToForward: List<String>,
    pendingContactData: ContactData?,
    pendingAttachments: List<PendingAttachmentInput>,
    attachmentQuality: AttachmentQuality,

    // Tutorial data
    sendButtonBounds: Rect,

    // Dismiss callbacks
    onDismissEffectPicker: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDismissBlockDialog: () -> Unit,
    onDismissSmsBlockedDialog: () -> Unit,
    onDismissVideoCallDialog: () -> Unit,
    onDismissDiscordSetupDialog: () -> Unit,
    onDismissDiscordHelpOverlay: () -> Unit,
    onDismissScheduleDialog: () -> Unit,
    onDismissVCardOptionsDialog: () -> Unit,
    onDismissQualitySheet: () -> Unit,
    onDismissForwardDialog: () -> Unit,
    onDismissRetrySheet: () -> Unit,

    // Action callbacks
    onEffectSelected: (effect: com.bothbubbles.ui.effects.MessageEffect?) -> Unit,
    onShowDiscordSetup: () -> Unit,
    onShowDiscordHelp: () -> Unit,
    onClearPendingContactData: () -> Unit,
    onClearMessageToForward: () -> Unit,
    onClearMessagesToForward: () -> Unit,
    onClearMessageSelection: () -> Unit,

    // Bubble mode - simplified UI for Android conversation bubbles
    isBubbleMode: Boolean = false
) {
    // Collect state internally from delegates to avoid ChatScreen recomposition
    val connectionState by connectionDelegate.state.collectAsStateWithLifecycle()
    val sendState by sendDelegate.state.collectAsStateWithLifecycle()
    val operationsState by operationsDelegate.state.collectAsStateWithLifecycle()
    val searchState by searchDelegate.state.collectAsStateWithLifecycle()

    // PERF FIX: Only collect forwardable chats when dialog is shown
    val forwardableChats by remember(showForwardDialog) {
        if (showForwardDialog) {
            viewModel.getForwardableChats()
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // PERF FIX: Move WhatsApp check to LaunchedEffect (not during composition)
    var isWhatsAppAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isWhatsAppAvailable = viewModel.operations.isWhatsAppAvailable(context)
    }

    // PERF FIX: Collect draftText here to avoid ChatScreen recomposition on every keystroke
    val draftText by viewModel.composer.draftText.collectAsStateWithLifecycle()

    // In bubble mode, skip most dialogs - only show RetryMessageBottomSheet
    if (isBubbleMode) {
        // Retry bottom sheet for failed messages (kept in bubble mode)
        selectedMessageForRetry?.let { failedMessage ->
            RetryMessageBottomSheet(
                messageGuid = failedMessage.guid,
                canRetryAsSms = canRetrySmsForMessage,
                contactIMessageAvailable = connectionState.contactIMessageAvailable == true,
                onRetryAsIMessage = {
                    viewModel.send.retryMessage(failedMessage.guid)
                    onDismissRetrySheet()
                },
                onRetryAsSms = {
                    viewModel.send.retryMessageAsSms(failedMessage.guid)
                    onDismissRetrySheet()
                },
                onDismiss = onDismissRetrySheet
            )
        }
        return
    }

    // Full dialog set for non-bubble mode
    // Effect picker bottom sheet
    if (showEffectPicker) {
        EffectPickerSheet(
            messageText = draftText,
            onEffectSelected = onEffectSelected,
            onDismiss = onDismissEffectPicker
        )
    }

    // Retry bottom sheet for failed messages
    selectedMessageForRetry?.let { failedMessage ->
        RetryMessageBottomSheet(
            messageGuid = failedMessage.guid,
            canRetryAsSms = canRetrySmsForMessage,
            contactIMessageAvailable = connectionState.contactIMessageAvailable == true,
            onRetryAsIMessage = {
                viewModel.send.retryMessage(failedMessage.guid)
                onDismissRetrySheet()
            },
            onRetryAsSms = {
                viewModel.send.retryMessageAsSms(failedMessage.guid)
                onDismissRetrySheet()
            },
            onDismiss = onDismissRetrySheet
        )
    }

    // Quality Selection Sheet
    QualitySelectionSheet(
        visible = showQualitySheet,
        currentQuality = attachmentQuality,
        onQualitySelected = { quality ->
            viewModel.composer.setAttachmentQuality(quality)
            onDismissQualitySheet()
        },
        onDismiss = onDismissQualitySheet
    )

    // Search Results Sheet
    SearchResultsSheet(
        visible = searchState.showResultsSheet,
        results = searchState.databaseResults,
        isSearching = searchState.isSearchingDatabase,
        query = searchState.query,
        onResultClick = { result ->
            viewModel.scrollToAndHighlightMessage(result.messageGuid)
            viewModel.search.hideResultsSheet()
        },
        onDismiss = viewModel.search::hideResultsSheet
    )

    // Delete conversation dialog
    if (showDeleteDialog) {
        DeleteConversationDialog(
            chatDisplayName = chatInfoState.chatTitle,
            onConfirm = {
                viewModel.operations.deleteChat()
                onDismissDeleteDialog()
            },
            onDismiss = onDismissDeleteDialog
        )
    }

    // Block and report dialog
    if (showBlockDialog) {
        BlockAndReportDialog(
            chatDisplayName = chatInfoState.chatTitle,
            isSmsChat = chatInfoState.isLocalSmsChat,
            onConfirm = { options: BlockOptions ->
                if (options.blockContact) {
                    if (viewModel.operations.blockContact(chatInfoState.participantPhone)) {
                        Toast.makeText(context, "Contact blocked", Toast.LENGTH_SHORT).show()
                    }
                }
                if (options.markAsSpam) {
                    viewModel.operations.reportAsSpam()
                    Toast.makeText(context, "Marked as spam", Toast.LENGTH_SHORT).show()
                }
                if (options.reportToCarrier) {
                    if (viewModel.operations.reportToCarrier()) {
                        Toast.makeText(context, "Reporting to carrier...", Toast.LENGTH_SHORT).show()
                    }
                }
                onDismissBlockDialog()
            },
            onDismiss = onDismissBlockDialog,
            alreadyReportedToCarrier = operationsState.isReportedToCarrier
        )
    }

    // SMS blocked dialog
    if (showSmsBlockedDialog) {
        SmsBlockedDialog(
            onOpenSettings = {
                onDismissSmsBlockedDialog()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                }
            },
            onDismiss = onDismissSmsBlockedDialog
        )
    }

    // Video call method dialog
    if (showVideoCallDialog) {
        VideoCallMethodDialog(
            onGoogleMeet = {
                try {
                    context.startActivity(viewModel.operations.getGoogleMeetIntent())
                } catch (e: android.content.ActivityNotFoundException) {
                    // Google Meet not installed or can't handle intent
                }
                onDismissVideoCallDialog()
            },
            onWhatsApp = {
                viewModel.operations.getWhatsAppCallIntent(chatInfoState.participantPhone)?.let { intent ->
                    context.startActivity(intent)
                }
                onDismissVideoCallDialog()
            },
            onDiscord = {
                chatInfoState.discordChannelId?.let { channelId ->
                    try {
                        context.startActivity(viewModel.operations.getDiscordCallIntent(channelId))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // Discord app may be installed but doesn't handle this deep link
                    }
                }
                onDismissVideoCallDialog()
            },
            onDiscordSetup = {
                onDismissVideoCallDialog()
                onShowDiscordSetup()
            },
            onDismiss = onDismissVideoCallDialog,
            isWhatsAppAvailable = isWhatsAppAvailable,
            isDiscordAvailable = viewModel.operations.isDiscordInstalled(),
            hasDiscordChannelId = chatInfoState.discordChannelId != null
        )
    }

    // Discord channel setup dialog
    if (showDiscordSetupDialog) {
        DiscordChannelSetupDialog(
            currentChannelId = chatInfoState.discordChannelId,
            contactName = chatInfoState.chatTitle,
            onSave = { channelId ->
                viewModel.operations.saveDiscordChannelId(chatInfoState.participantPhone, channelId)
                viewModel.chatInfo.refreshDiscordChannelId()
                onDismissDiscordSetupDialog()
            },
            onClear = {
                viewModel.operations.clearDiscordChannelId(chatInfoState.participantPhone)
                viewModel.chatInfo.refreshDiscordChannelId()
                onDismissDiscordSetupDialog()
            },
            onDismiss = onDismissDiscordSetupDialog,
            onShowHelp = onShowDiscordHelp
        )
    }

    // Discord help overlay
    if (showDiscordHelpOverlay) {
        DiscordChannelHelpOverlay(
            onDismiss = onDismissDiscordHelpOverlay
        )
    }

    // Schedule message dialog
    ScheduleMessageDialog(
        visible = showScheduleDialog,
        onDismiss = onDismissScheduleDialog,
        onSchedule = { timestamp ->
            viewModel.scheduledMessages.scheduleMessage(
                text = draftText,
                attachments = pendingAttachments,
                sendAt = timestamp
            )
            viewModel.updateDraft("")
            viewModel.composer.clearAttachments()

            val dateFormat = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault())
            Toast.makeText(
                context,
                "Scheduled for ${dateFormat.format(java.util.Date(timestamp))}. Phone must be on to send.",
                Toast.LENGTH_LONG
            ).show()

            onDismissScheduleDialog()
        }
    )

    // vCard options dialog
    VCardOptionsDialog(
        visible = showVCardOptionsDialog,
        contactData = pendingContactData,
        onDismiss = {
            onDismissVCardOptionsDialog()
            onClearPendingContactData()
        },
        onConfirm = { options ->
            pendingContactData?.let { contactData ->
                val success = viewModel.composer.addContactAsVCard(contactData, options)
                if (!success) {
                    Toast.makeText(context, "Failed to create contact card", Toast.LENGTH_SHORT).show()
                }
            }
            onDismissVCardOptionsDialog()
            onClearPendingContactData()
        }
    )

    // Forward message dialog - handles both single and multiple message forwarding
    ForwardMessageDialog(
        visible = showForwardDialog,
        onDismiss = {
            onDismissForwardDialog()
            onClearMessageToForward()
            onClearMessagesToForward()
        },
        onChatSelected = { targetChatGuid ->
            // Multi-message forward takes priority
            if (messagesToForward.isNotEmpty()) {
                viewModel.send.forwardMessages(messagesToForward, targetChatGuid)
            } else {
                messageToForward?.let { message ->
                    viewModel.send.forwardMessage(message.guid, targetChatGuid)
                }
            }
        },
        chats = forwardableChats.map { chat ->
            ForwardableChatInfo(
                guid = chat.sourceId,  // Use sourceId (chat GUID) not id (unified chat ID)
                displayName = chat.displayName ?: PhoneNumberFormatter.format(chat.normalizedAddress),
                isGroup = chat.isGroup
            )
        },
        isForwarding = sendState.isForwarding
    )

    // Handle forward success
    LaunchedEffect(sendState.forwardSuccess) {
        if (sendState.forwardSuccess) {
            val count = messagesToForward.size.takeIf { it > 0 } ?: 1
            val message = if (count == 1) "Message forwarded" else "$count messages forwarded"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onDismissForwardDialog()
            onClearMessageToForward()
            onClearMessagesToForward()
            onClearMessageSelection() // Clear selection after successful forward
            viewModel.send.clearForwardSuccess()
        }
    }

    // Tutorial overlay
    val tutorialState = if (sendButtonBounds != Rect.Zero) {
        connectionState.tutorialState.toComposerTutorialState()
    } else {
        com.bothbubbles.ui.chat.composer.ComposerTutorialState.Hidden
    }

    ComposerTutorial(
        tutorialState = tutorialState,
        sendButtonBounds = sendButtonBounds,
        onStepComplete = { _ ->
            // The tutorial progresses based on actual swipe gestures
        },
        onDismiss = {
            viewModel.sendMode.updateTutorialState(TutorialState.COMPLETED)
        }
    )
}
