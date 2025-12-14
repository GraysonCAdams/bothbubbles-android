package com.bothbubbles.ui.chat.delegates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import com.bothbubbles.data.repository.ChatRepository
import com.bothbubbles.services.spam.SpamReportingService
import com.bothbubbles.services.spam.SpamRepository
import com.bothbubbles.util.error.AppError
import com.bothbubbles.util.error.ValidationError
import com.bothbubbles.util.error.handle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delegate responsible for chat menu actions and operations.
 * Handles archive, star, delete, spam reporting, and contact-related actions.
 */
class ChatOperationsDelegate @Inject constructor(
    private val chatRepository: ChatRepository,
    private val spamRepository: SpamRepository,
    private val spamReportingService: SpamReportingService
) {

    private lateinit var chatGuid: String
    private lateinit var scope: CoroutineScope

    // Operation state
    private val _isArchived = MutableStateFlow(false)
    val isArchived: StateFlow<Boolean> = _isArchived.asStateFlow()

    private val _isStarred = MutableStateFlow(false)
    val isStarred: StateFlow<Boolean> = _isStarred.asStateFlow()

    private val _chatDeleted = MutableStateFlow(false)
    val chatDeleted: StateFlow<Boolean> = _chatDeleted.asStateFlow()

    private val _showSubjectField = MutableStateFlow(false)
    val showSubjectField: StateFlow<Boolean> = _showSubjectField.asStateFlow()

    private val _isReportedToCarrier = MutableStateFlow(false)
    val isReportedToCarrier: StateFlow<Boolean> = _isReportedToCarrier.asStateFlow()

    private val _operationError = MutableStateFlow<AppError?>(null)
    val operationError: StateFlow<AppError?> = _operationError.asStateFlow()

    /**
     * Initialize the delegate.
     */
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid
        this.scope = scope
    }

    /**
     * Update state from external sources.
     */
    fun updateState(
        isArchived: Boolean,
        isStarred: Boolean,
        isReportedToCarrier: Boolean
    ) {
        _isArchived.value = isArchived
        _isStarred.value = isStarred
        _isReportedToCarrier.value = isReportedToCarrier
    }

    /**
     * Archive the chat.
     */
    fun archiveChat() {
        scope.launch {
            chatRepository.setArchived(chatGuid, true).handle(
                onSuccess = {
                    _isArchived.value = true
                },
                onError = { appError ->
                    _operationError.value = appError
                }
            )
        }
    }

    /**
     * Unarchive the chat.
     */
    fun unarchiveChat() {
        scope.launch {
            chatRepository.setArchived(chatGuid, false).handle(
                onSuccess = {
                    _isArchived.value = false
                },
                onError = { appError ->
                    _operationError.value = appError
                }
            )
        }
    }

    /**
     * Toggle starred status.
     */
    fun toggleStarred() {
        val currentStarred = _isStarred.value
        scope.launch {
            chatRepository.setStarred(chatGuid, !currentStarred).handle(
                onSuccess = {
                    _isStarred.value = !currentStarred
                },
                onError = { appError ->
                    _operationError.value = appError
                }
            )
        }
    }

    /**
     * Delete the chat.
     */
    fun deleteChat() {
        scope.launch {
            chatRepository.deleteChat(chatGuid).handle(
                onSuccess = {
                    _chatDeleted.value = true
                },
                onError = { appError ->
                    _operationError.value = appError
                }
            )
        }
    }

    /**
     * Toggle subject field visibility.
     */
    fun toggleSubjectField() {
        _showSubjectField.value = !_showSubjectField.value
    }

    /**
     * Mark chat as safe (not spam).
     */
    fun markAsSafe() {
        scope.launch {
            spamRepository.markAsSafe(chatGuid)
        }
    }

    /**
     * Report chat as spam.
     */
    fun reportAsSpam() {
        scope.launch {
            spamRepository.reportAsSpam(chatGuid)
        }
    }

    /**
     * Report spam to carrier via 7726.
     */
    fun reportToCarrier(): Boolean {
        scope.launch {
            val result = spamReportingService.reportToCarrier(chatGuid)
            if (result is SpamReportingService.ReportResult.Success) {
                _isReportedToCarrier.value = true
            }
        }
        return true
    }

    /**
     * Check if chat has been reported to carrier.
     */
    fun checkReportedToCarrier() {
        scope.launch {
            val isReported = spamReportingService.isReportedToCarrier(chatGuid)
            _isReportedToCarrier.value = isReported
        }
    }

    /**
     * Create intent to add contact.
     */
    fun getAddToContactsIntent(participantPhone: String?, inferredName: String?): Intent {
        val phone = participantPhone ?: ""
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (inferredName != null) {
                putExtra(ContactsContract.Intents.Insert.NAME, inferredName)
            }
        }
    }

    /**
     * Create intent for Google Meet.
     */
    fun getGoogleMeetIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new"))
    }

    /**
     * Create intent for WhatsApp call.
     */
    fun getWhatsAppCallIntent(participantPhone: String?): Intent? {
        val phone = participantPhone?.replace(Regex("[^0-9+]"), "") ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone"))
    }

    /**
     * Create intent for help page.
     */
    fun getHelpIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlueBubblesApp/bluebubbles-app/issues"))
    }

    /**
     * Block a contact (SMS only).
     */
    fun blockContact(context: Context, participantPhone: String?): Boolean {
        val phone = participantPhone ?: return false

        return try {
            val values = android.content.ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phone)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            true
        } catch (e: Exception) {
            _operationError.value = ValidationError.InvalidInput("contact", "Failed to block: ${e.message ?: "unknown error"}")
            false
        }
    }

    /**
     * Check if WhatsApp is installed.
     */
    fun isWhatsAppAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear operation error.
     */
    fun clearError() {
        _operationError.value = null
    }
}
