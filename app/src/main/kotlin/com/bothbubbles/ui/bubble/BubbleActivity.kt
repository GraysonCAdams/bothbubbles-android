package com.bothbubbles.ui.bubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.ui.chat.ChatScreen
import com.bothbubbles.ui.theme.BothBubblesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity that hosts the chat bubble UI.
 * This activity is displayed inside an Android conversation bubble,
 * providing a compact chat interface for quick replies.
 *
 * Key features:
 * - Full chat functionality via ChatScreen with isBubbleMode=true
 * - Camera and media capture
 * - Pagination/scrolling through message history
 * - Tapback reactions and swipe to reply
 * - Voice memo recording
 * - GIF picker
 *
 * Note: This activity registers with [ActiveConversationManager] to suppress
 * notifications for the active chat while the bubble is open, following
 * Android's conversation bubble best practices.
 */
@AndroidEntryPoint
class BubbleActivity : ComponentActivity() {

    @Inject
    lateinit var activeConversationManager: ActiveConversationManager

    private var currentChatGuid: String? = null

    companion object {
        // Note: We use EXTRA_CHAT_GUID for notification compatibility
        // but also set "chatGuid" for SavedStateHandle compatibility with ChatViewModel
        const val EXTRA_CHAT_GUID = NotificationChannelManager.EXTRA_CHAT_GUID
        const val EXTRA_CHAT_TITLE = "chat_title"
        // Keys matching ChatViewModel's SavedStateHandle expectations
        private const val SAVED_STATE_CHAT_GUID = "chatGuid"
        private const val SAVED_STATE_MERGED_GUIDS = "mergedGuids"
        private const val SAVED_STATE_IS_BUBBLE_MODE = "isBubbleMode"

        /**
         * Create an intent to launch the bubble activity for a specific chat.
         * @param mergedGuids Comma-separated list of merged chat GUIDs for unified chat support
         */
        fun createIntent(context: Context, chatGuid: String, chatTitle: String, mergedGuids: String? = null): Intent {
            return Intent(context, BubbleActivity::class.java).apply {
                putExtra(EXTRA_CHAT_GUID, chatGuid)
                // Also set with the key ChatViewModel expects from SavedStateHandle
                putExtra(SAVED_STATE_CHAT_GUID, chatGuid)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                // Pass mergedGuids for unified chat support
                if (mergedGuids != null) {
                    putExtra(SAVED_STATE_MERGED_GUIDS, mergedGuids)
                }
                // Mark as bubble mode so ChatViewModel doesn't cancel the notification
                putExtra(SAVED_STATE_IS_BUBBLE_MODE, true)
                // Required flags for bubble activities
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Note: Do NOT use enableEdgeToEdge() for bubbles - they are floating windows
        // that don't have their own system bars. Using edge-to-edge causes incorrect
        // WindowInsets reporting, resulting in extra dead space when the keyboard opens.
        super.onCreate(savedInstanceState)

        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID) ?: run {
            finish()
            return
        }

        currentChatGuid = chatGuid

        // Register this bubble as the active conversation to suppress notifications
        activeConversationManager.setActiveConversation(chatGuid)

        setContent {
            BothBubblesTheme {
                ChatScreen(
                    chatGuid = chatGuid,
                    onBackClick = { finish() },
                    onDetailsClick = { openInFullApp(chatGuid) },
                    onMediaClick = { /* Media viewing handled by ChatScreen internally */ },
                    isBubbleMode = true
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-register when bubble becomes visible again
        currentChatGuid?.let { chatGuid ->
            activeConversationManager.setActiveConversation(chatGuid)
        }
    }

    override fun onPause() {
        super.onPause()
        // Clear active conversation when bubble is hidden
        // This ensures notifications show when bubble is collapsed
        activeConversationManager.clearActiveConversation()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure cleanup on destroy
        activeConversationManager.clearActiveConversation()
    }

    /**
     * Open the full chat screen in the main app.
     */
    private fun openInFullApp(chatGuid: String) {
        val mergedGuids = intent.getStringExtra(SAVED_STATE_MERGED_GUIDS)
        val fullAppIntent = Intent(this, com.bothbubbles.MainActivity::class.java).apply {
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            if (mergedGuids != null) {
                putExtra(NotificationChannelManager.EXTRA_MERGED_GUIDS, mergedGuids)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(fullAppIntent)
        finish()
    }
}
