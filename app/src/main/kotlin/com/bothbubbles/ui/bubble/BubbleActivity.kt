package com.bothbubbles.ui.bubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bothbubbles.services.ActiveConversationManager
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.ui.theme.BothBubblesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity that hosts the chat bubble UI.
 * This activity is displayed inside an Android conversation bubble,
 * providing a compact chat interface for quick replies.
 *
 * Key features:
 * - Compact message composer
 * - Recent message history
 * - Quick reply functionality
 * - Expand to full app option
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
        const val EXTRA_CHAT_GUID = NotificationChannelManager.EXTRA_CHAT_GUID
        const val EXTRA_CHAT_TITLE = "chat_title"

        /**
         * Create an intent to launch the bubble activity for a specific chat.
         */
        fun createIntent(context: Context, chatGuid: String, chatTitle: String): Intent {
            return Intent(context, BubbleActivity::class.java).apply {
                putExtra(EXTRA_CHAT_GUID, chatGuid)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                // Required flags for bubble activities
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID) ?: run {
            finish()
            return
        }
        val chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE) ?: ""

        currentChatGuid = chatGuid

        // Register this bubble as the active conversation to suppress notifications
        activeConversationManager.setActiveConversation(chatGuid)

        setContent {
            BothBubblesTheme {
                BubbleChatScreen(
                    chatGuid = chatGuid,
                    chatTitle = chatTitle,
                    onExpandClick = { openInFullApp(chatGuid) },
                    onCloseClick = { finish() }
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
        val intent = Intent(this, com.bothbubbles.MainActivity::class.java).apply {
            putExtra(EXTRA_CHAT_GUID, chatGuid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}
