package com.bothbubbles.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.model.PendingAttachmentInput
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.ui.chat.composer.tutorial.ComposerTutorial
import com.bothbubbles.ui.chat.composer.tutorial.toComposerTutorialState
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.ui.chat.components.QualitySelectionSheet
import com.bothbubbles.ui.chat.components.SearchResultsSheet
import com.bothbubbles.ui.chat.state.ChatConnectionState
import com.bothbubbles.ui.chat.state.SearchState
import com.bothbubbles.ui.chat.state.ChatInfoState
import com.bothbubbles.ui.chat.state.OperationsState
import com.bothbubbles.ui.chat.state.SendState
import com.bothbubbles.ui.components.dialogs.DiscordChannelHelpOverlay
import com.bothbubbles.ui.components.dialogs.DiscordChannelSetupDialog
import com.bothbubbles.ui.components.dialogs.ForwardableChatInfo
import com.bothbubbles.ui.components.dialogs.ForwardMessageDialog
import com.bothbubbles.ui.components.dialogs.VCardOptionsDialog
import com.bothbubbles.ui.components.input.ScheduleMessageDialog
import com.bothbubbles.ui.components.message.MessageUiModel
import com.bothbubbles.ui.effects.EffectPickerSheet
import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Hosts all dialogs and bottom sheets for the ChatScreen.
 * Extracted to reduce ChatScreen complexity and improve maintainability.
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
 */
@Composable
fun ChatDialogsHost(
    // ViewModel for operations
    viewModel: ChatViewModel,
    context: Context,

    // State objects
    chatInfoState: ChatInfoState,
    connectionState: ChatConnectionState,
    operationsState: OperationsState,
    sendState: SendState,
    searchState: SearchState,

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
    pendingContactData: ContactData?,
    forwardableChats: List<ChatEntity>,
    draftText: String,
    pendingAttachments: List<PendingAttachmentInput>,
    attachmentQuality: AttachmentQuality,
    isWhatsAppAvailable: Boolean,

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
) {
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
                    if (viewModel.operations.blockContact(context, chatInfoState.participantPhone)) {
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
                context.startActivity(viewModel.operations.getGoogleMeetIntent())
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
                    context.startActivity(viewModel.operations.getDiscordCallIntent(channelId))
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

    // Forward message dialog
    ForwardMessageDialog(
        visible = showForwardDialog,
        onDismiss = {
            onDismissForwardDialog()
            onClearMessageToForward()
        },
        onChatSelected = { targetChatGuid ->
            messageToForward?.let { message ->
                viewModel.send.forwardMessage(message.guid, targetChatGuid)
            }
        },
        chats = forwardableChats.map { chat ->
            ForwardableChatInfo(
                guid = chat.guid,
                displayName = chat.displayName ?: chat.chatIdentifier?.let { PhoneNumberFormatter.format(it) } ?: "",
                isGroup = chat.isGroup
            )
        },
        isForwarding = sendState.isForwarding
    )

    // Handle forward success
    LaunchedEffect(sendState.forwardSuccess) {
        if (sendState.forwardSuccess) {
            Toast.makeText(context, "Message forwarded", Toast.LENGTH_SHORT).show()
            onDismissForwardDialog()
            onClearMessageToForward()
            viewModel.send.clearForwardSuccess()
        }
    }

    // Tutorial overlay
    val effectiveTutorialState = if (sendButtonBounds != Rect.Zero) {
        connectionState.tutorialState.toComposerTutorialState()
    } else {
        com.bothbubbles.ui.chat.composer.ComposerTutorialState.Hidden
    }

    ComposerTutorial(
        tutorialState = effectiveTutorialState,
        sendButtonBounds = sendButtonBounds,
        onStepComplete = { _ ->
            // The tutorial progresses based on actual swipe gestures
        },
        onDismiss = {
            viewModel.sendMode.updateTutorialState(TutorialState.COMPLETED)
        }
    )
}
