package com.bothbubbles.services.contacts.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Handles intents when user taps on a BothBubbles group contact.
 *
 * This activity receives intents from the system Contacts app when a user
 * taps "Send Message" on a group contact synced by BothBubbles.
 *
 * The intent contains our custom MIME type with the unified chat ID, which we
 * resolve to a chat GUID and use to open the correct chat in the main app.
 */
class GroupContactIntentActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface IntentEntryPoint {
        fun unifiedChatDao(): UnifiedChatDao
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
        finish()
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: run {
            Timber.w("No data in group contact intent")
            launchMainActivity(null)
            return
        }

        // Extract the unified chat ID from the contact data
        val unifiedChatId = extractUnifiedChatId(data)

        if (unifiedChatId != null) {
            // Resolve unified chat ID to chat GUID (sourceId)
            val chatGuid = resolveToChatGuid(unifiedChatId)
            if (chatGuid != null) {
                Timber.d("Opening group chat from contact tap: unifiedId=$unifiedChatId, chatGuid=$chatGuid")
                launchMainActivity(chatGuid)
            } else {
                Timber.w("Could not resolve unified chat ID to chat GUID: $unifiedChatId")
                launchMainActivity(null)
            }
        } else {
            Timber.w("Could not extract unified chat ID from contact data: $data")
            launchMainActivity(null)
        }
    }

    private fun extractUnifiedChatId(data: android.net.Uri): String? {
        // The URI is typically: content://com.android.contacts/data/{id}
        // We need to query the contact data to get our custom DATA1 field (unified chat ID)

        val projection = arrayOf(ContactsContract.Data.DATA1)
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf("vnd.android.cursor.item/vnd.com.bothbubbles.group")

        return try {
            contentResolver.query(
                data,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    // Try without selection - the URI might directly point to our data row
                    contentResolver.query(data, projection, null, null, null)?.use { directCursor ->
                        if (directCursor.moveToFirst()) {
                            directCursor.getString(0)
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error querying contact data")
            null
        }
    }

    /**
     * Resolve unified chat ID to the primary chat GUID (sourceId).
     */
    private fun resolveToChatGuid(unifiedChatId: String): String? {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                IntentEntryPoint::class.java
            )
            val unifiedChatDao = entryPoint.unifiedChatDao()

            // Use runBlocking since this is a quick DB lookup and we're about to finish anyway
            runBlocking {
                unifiedChatDao.getById(unifiedChatId)?.sourceId
            }
        } catch (e: Exception) {
            Timber.e(e, "Error resolving unified chat ID to chat GUID")
            null
        }
    }

    private fun launchMainActivity(chatGuid: String?) {
        val mainIntent = Intent(this, com.bothbubbles.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

            if (chatGuid != null) {
                // Pass chat GUID as deep link
                action = Intent.ACTION_VIEW
                this.data = android.net.Uri.parse("bothbubbles://chat/$chatGuid")
            }
        }

        startActivity(mainIntent)
    }
}
