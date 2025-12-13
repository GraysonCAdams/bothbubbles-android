package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.util.PhoneNumberFormatter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto screen for composing a new message.
 * Allows users to select a recent contact and dictate a message using voice.
 *
 * Flow:
 * 1. Show list of recent contacts/conversations
 * 2. User selects a recipient
 * 3. User dictates message via voice
 * 4. Message is sent
 */
class ComposeMessageScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageSendingService: MessageSendingService,
    private val onMessageSent: () -> Unit
) : Screen(carContext) {

    // Screen-local scope that follows screen lifecycle
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recentContacts: List<ContactItem> = emptyList()

    @Volatile
    private var isLoading = true

    @Volatile
    private var selectedContact: ContactItem? = null

    init {
        // Register lifecycle observer to cancel scope when screen is destroyed
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        loadRecentContacts()
    }

    private fun loadRecentContacts() {
        screenScope.launch {
            try {
                // Get recent chats to extract contacts
                val chats = chatDao.getActiveChats().first()
                    .sortedByDescending { it.lastMessageDate ?: 0L }
                    .take(MAX_RECENT_CONTACTS)

                // PERF: Batch fetch all participants in a single query
                val chatGuids = chats.map { it.guid }
                val participantsByChat = chatDao.getParticipantsWithChatGuids(chatGuids)
                    .groupBy({ it.chatGuid }, { it.handle })

                val contacts = chats.mapNotNull { chat ->
                    // Get the primary participant (non-self) for each chat
                    val participants = participantsByChat[chat.guid] ?: emptyList()
                    val primaryParticipant = participants.firstOrNull()

                    if (primaryParticipant != null) {
                        ContactItem(
                            displayName = chat.displayName
                                ?: primaryParticipant.cachedDisplayName
                                ?: primaryParticipant.inferredName
                                ?: primaryParticipant.formattedAddress
                                ?: PhoneNumberFormatter.format(primaryParticipant.address),
                            address = primaryParticipant.address,
                            chatGuid = chat.guid,
                            isGroupChat = participants.size > 1
                        )
                    } else {
                        // Fallback for chats without participants
                        chat.chatIdentifier?.let { identifier ->
                            ContactItem(
                                displayName = chat.displayName ?: PhoneNumberFormatter.format(identifier),
                                address = identifier,
                                chatGuid = chat.guid,
                                isGroupChat = false
                            )
                        }
                    }
                }

                recentContacts = contacts
                isLoading = false
                invalidate()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load contacts", e)
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        // If a contact is selected, show voice input template
        selectedContact?.let { contact ->
            return buildVoiceInputTemplate(contact)
        }

        // Otherwise show contact selection list
        return buildContactListTemplate()
    }

    private fun buildContactListTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        for (contact in recentContacts) {
            val icon = if (contact.isGroupChat) {
                android.R.drawable.ic_menu_myplaces // Group icon
            } else {
                android.R.drawable.ic_menu_call // Contact icon
            }

            val row = Row.Builder()
                .setTitle(contact.displayName)
                .addText(if (contact.isGroupChat) "Group" else contact.address)
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, icon)
                    ).build()
                )
                .setOnClickListener {
                    selectedContact = contact
                    invalidate()
                }
                .build()

            itemListBuilder.addItem(row)
        }

        if (recentContacts.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoading) "Loading contacts..." else "No recent contacts"
            )
        }

        return ListTemplate.Builder()
            .setTitle("Select Recipient")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildVoiceInputTemplate(contact: ContactItem): Template {
        // Use MessageTemplate for voice input - this triggers the car's voice assistant
        val sendAction = Action.Builder()
            .setTitle("Dictate Message")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_btn_speak_now)
                )
                    .setTint(CarColor.BLUE)
                    .build()
            )
            .setOnClickListener(
                ParkedOnlyOnClickListener.create {
                    // Launch voice input via the car's voice assistant
                    startVoiceInput(contact)
                }
            )
            .build()

        val backAction = Action.Builder()
            .setTitle("Back")
            .setOnClickListener {
                selectedContact = null
                invalidate()
            }
            .build()

        return MessageTemplate.Builder("Tap \"Dictate\" to speak your message to ${contact.displayName}")
            .setTitle("New Message")
            .setHeaderAction(Action.BACK)
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
                ).build()
            )
            .addAction(sendAction)
            .addAction(backAction)
            .build()
    }

    private fun startVoiceInput(contact: ContactItem) {
        // Use CarContext to start voice input
        // The voice input result will come back through onNewIntent or a callback
        try {
            // For Android Auto, we can use the built-in voice recognition
            // This opens the voice input interface
            val voiceIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say your message to ${contact.displayName}")
            }

            // Note: In Android Auto, voice input is typically handled by the car's voice assistant
            // For now, show a toast and navigate to the conversation where they can use voice reply
            CarToast.makeText(
                carContext,
                "Opening conversation for voice reply...",
                CarToast.LENGTH_SHORT
            ).show()

            // Navigate to the conversation detail screen where voice reply is available
            screenScope.launch {
                val chat = chatDao.getChatByGuid(contact.chatGuid)
                if (chat != null) {
                    screenManager.popToRoot()
                    screenManager.push(
                        VoiceReplyScreen(
                            carContext = carContext,
                            chat = chat,
                            messageSendingService = messageSendingService,
                            onMessageSent = {
                                onMessageSent()
                                screenManager.popToRoot()
                            }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start voice input", e)
            CarToast.makeText(
                carContext,
                "Voice input not available",
                CarToast.LENGTH_SHORT
            ).show()
        }
    }

    data class ContactItem(
        val displayName: String,
        val address: String,
        val chatGuid: String,
        val isGroupChat: Boolean
    )

    companion object {
        private const val TAG = "ComposeMessageScreen"
        private const val MAX_RECENT_CONTACTS = 20
    }
}
