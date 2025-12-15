package com.bothbubbles.services.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bothbubbles.R
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.services.messaging.MessageSendingService
import com.bothbubbles.services.socket.SocketConnection
import com.bothbubbles.util.PhoneNumberFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto screen for searching contacts via voice or keyboard.
 *
 * Uses SearchTemplate for native voice search integration:
 * - Voice input triggers search automatically
 * - Results shown as list of matching contacts
 * - Tap contact to compose message
 */
class VoiceSearchScreen(
    carContext: CarContext,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val messageSendingService: MessageSendingService,
    private val socketConnection: SocketConnection?,
    private val onContactSelected: (ChatEntity) -> Unit
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var searchQuery: String = ""

    @Volatile
    private var searchResults: List<SearchResult> = emptyList()

    @Volatile
    private var isSearching: Boolean = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        if (searchResults.isNotEmpty()) {
            for (result in searchResults) {
                val row = Row.Builder()
                    .setTitle(result.displayName)
                    .addText(result.subtitle)
                    .setImage(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext,
                                if (result.isGroup) android.R.drawable.ic_menu_myplaces
                                else R.mipmap.ic_launcher
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        selectContact(result)
                    }
                    .build()
                itemListBuilder.addItem(row)
            }
        } else if (searchQuery.isNotEmpty() && !isSearching) {
            itemListBuilder.setNoItemsMessage("No contacts found for \"$searchQuery\"")
        } else if (searchQuery.isEmpty()) {
            itemListBuilder.setNoItemsMessage("Say or type a contact name to search")
        }

        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                searchQuery = searchText
                if (searchText.length >= 2) {
                    performSearch(searchText)
                } else if (searchText.isEmpty()) {
                    searchResults = emptyList()
                    invalidate()
                }
            }

            override fun onSearchSubmitted(searchText: String) {
                searchQuery = searchText
                if (searchText.isNotEmpty()) {
                    performSearch(searchText)
                }
            }
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false) // Voice input by default
            .setItemList(itemListBuilder.build())
            .setInitialSearchText(searchQuery)
            .build()
    }

    private fun performSearch(query: String) {
        isSearching = true
        invalidate()

        screenScope.launch {
            try {
                val results = mutableListOf<SearchResult>()
                val queryLower = query.lowercase()

                // Search in existing chats
                val chats = chatDao.getActiveChats().first()

                // Get participants for all chats
                val chatGuids = chats.map { it.guid }
                val participantsByChat = chatDao.getParticipantsWithChatGuids(chatGuids)
                    .groupBy({ it.chatGuid }, { it.handle })

                for (chat in chats) {
                    val participants = participantsByChat[chat.guid] ?: emptyList()

                    // Check chat display name
                    val displayName = chat.displayName
                    if (displayName != null && displayName.lowercase().contains(queryLower)) {
                        results.add(
                            SearchResult(
                                chatGuid = chat.guid,
                                displayName = displayName,
                                subtitle = if (participants.size > 1) "${participants.size} participants" else "",
                                isGroup = participants.size > 1
                            )
                        )
                        continue
                    }

                    // Check participant names
                    for (participant in participants) {
                        val name = participant.cachedDisplayName
                            ?: participant.inferredName
                            ?: participant.formattedAddress
                            ?: PhoneNumberFormatter.format(participant.address)

                        if (name.lowercase().contains(queryLower) ||
                            participant.address.contains(queryLower)) {
                            results.add(
                                SearchResult(
                                    chatGuid = chat.guid,
                                    displayName = chat.displayName ?: name,
                                    subtitle = if (chat.displayName != null) name else participant.address,
                                    isGroup = participants.size > 1
                                )
                            )
                            break // Only add chat once
                        }
                    }
                }

                // Sort by relevance (exact match first)
                searchResults = results.sortedWith(
                    compareByDescending<SearchResult> { it.displayName.lowercase() == queryLower }
                        .thenByDescending { it.displayName.lowercase().startsWith(queryLower) }
                        .thenBy { it.displayName.lowercase() }
                ).take(MAX_RESULTS)

                isSearching = false
                invalidate()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Search failed", e)
                isSearching = false
                invalidate()
            }
        }
    }

    private fun selectContact(result: SearchResult) {
        screenScope.launch {
            try {
                val chat = chatDao.getChatByGuid(result.chatGuid)
                if (chat != null) {
                    onContactSelected(chat)
                } else {
                    CarToast.makeText(carContext, "Contact not found", CarToast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to select contact", e)
                CarToast.makeText(carContext, "Error selecting contact", CarToast.LENGTH_SHORT).show()
            }
        }
    }

    data class SearchResult(
        val chatGuid: String,
        val displayName: String,
        val subtitle: String,
        val isGroup: Boolean
    )

    companion object {
        private const val TAG = "VoiceSearchScreen"
        private const val MAX_RESULTS = 10
    }
}
