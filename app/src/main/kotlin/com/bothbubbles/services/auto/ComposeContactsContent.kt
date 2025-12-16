package com.bothbubbles.services.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Content delegate for the Compose tab in Android Auto.
 *
 * Shows recent contacts for quick message composition.
 * Uses ConversationItem API for consistent UI with the Chats tab.
 *
 * This reduces tap count for compose flow from 2 to 1 by being
 * immediately accessible via tab navigation.
 */
class ComposeContactsContent(
    private val carContext: CarContext,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageSendingService: MessageSendingService,
    private val socketConnection: SocketConnection?,
    private val screenManager: ScreenManager,
    private val onInvalidate: () -> Unit,
    private val onMessageSent: () -> Unit
) {
    private val contentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recentContacts: List<ContactItem> = emptyList()

    @Volatile
    private var isLoading = true

    // Avatar bitmap cache for async loading
    private val avatarCache = mutableMapOf<String, Bitmap?>()

    @Volatile
    private var avatarsLoading = false

    init {
        loadRecentContacts()
    }

    private fun loadRecentContacts() {
        contentScope.launch {
            try {
                val chats = chatDao.getActiveChats().first()
                    .sortedByDescending { it.lastMessageDate ?: 0L }
                    .take(MAX_RECENT_CONTACTS)

                val chatGuids = chats.map { it.guid }
                val participantsByChat = chatDao.getParticipantsWithChatGuids(chatGuids)
                    .groupBy({ it.chatGuid }, { it.handle })

                val contacts = chats.mapNotNull { chat ->
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
                            isGroupChat = participants.size > 1,
                            cachedAvatarPath = primaryParticipant.cachedAvatarPath
                        )
                    } else {
                        chat.chatIdentifier?.let { identifier ->
                            ContactItem(
                                displayName = chat.displayName ?: PhoneNumberFormatter.format(identifier),
                                address = identifier,
                                chatGuid = chat.guid,
                                isGroupChat = false,
                                cachedAvatarPath = null
                            )
                        }
                    }
                }

                recentContacts = contacts
                isLoading = false
                onInvalidate()

                // Start async avatar loading
                loadAvatarsAsync(contacts)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load contacts")
                isLoading = false
                onInvalidate()
            }
        }
    }

    /**
     * Async avatar loading with invalidation pattern.
     */
    private fun loadAvatarsAsync(contacts: List<ContactItem>) {
        if (avatarsLoading) return
        avatarsLoading = true

        contentScope.launch {
            var anyLoaded = false

            for (contact in contacts) {
                if (avatarCache.containsKey(contact.chatGuid)) continue

                val avatarBitmap = contact.cachedAvatarPath?.let { path ->
                    try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(path)?.let { bitmap ->
                                Bitmap.createScaledBitmap(bitmap, AVATAR_SIZE, AVATAR_SIZE, true)
                            }
                        } else null
                    } catch (e: Exception) {
                        Timber.d(e, "Failed to load avatar for ${contact.chatGuid}")
                        null
                    }
                }

                avatarCache[contact.chatGuid] = avatarBitmap
                if (avatarBitmap != null) anyLoaded = true
            }

            avatarsLoading = false

            if (anyLoaded) {
                onInvalidate()
            }
        }
    }

    fun buildContent(): Template {
        val itemListBuilder = ItemList.Builder()

        for (contact in recentContacts) {
            val item = buildContactItem(contact)
            itemListBuilder.addItem(item)
        }

        if (recentContacts.isEmpty()) {
            itemListBuilder.setNoItemsMessage(
                if (isLoading) "Loading contacts..." else "No recent contacts"
            )
        }

        return ListTemplate.Builder()
            .setTitle("New Message")
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildContactItem(contact: ContactItem): Row {
        // Use cached avatar or placeholder
        val icon = avatarCache[contact.chatGuid]?.let { bitmap ->
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        } ?: CarIcon.Builder(
            IconCompat.createWithResource(
                carContext,
                if (contact.isGroupChat) android.R.drawable.ic_menu_myplaces
                else R.mipmap.ic_launcher
            )
        ).build()

        val subtitle = if (contact.isGroupChat) "Group" else contact.address

        return Row.Builder()
            .setTitle(contact.displayName)
            .addText(subtitle)
            .setImage(icon)
            .setOnClickListener {
                navigateToVoiceReply(contact)
            }
            .build()
    }

    private fun navigateToVoiceReply(contact: ContactItem) {
        contentScope.launch {
            val chat = chatDao.getChatByGuid(contact.chatGuid)
            if (chat != null) {
                screenManager.push(
                    VoiceReplyScreen(
                        carContext = carContext,
                        chat = chat,
                        messageSendingService = messageSendingService,
                        onMessageSent = {
                            onMessageSent()
                        },
                        socketConnection = socketConnection
                    )
                )
            }
        }
    }

    data class ContactItem(
        val displayName: String,
        val address: String,
        val chatGuid: String,
        val isGroupChat: Boolean,
        val cachedAvatarPath: String?
    )

    companion object {
        private const val MAX_RECENT_CONTACTS = 20
        private const val AVATAR_SIZE = 64
    }
}
