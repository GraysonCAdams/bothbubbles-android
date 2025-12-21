package com.bothbubbles.services.contacts.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.provider.ContactsContract
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.util.GroupAvatarRenderer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Manages synchronization of group chats to system contacts.
 *
 * Group chats are synced as contacts with format: "Group Name (BothBubbles)"
 * This allows Google Assistant to match voice commands like "Send a message to Family Group".
 *
 * Features:
 * - Creates/updates/deletes contacts to match current group chats
 * - Uses custom MIME type for intent handling
 * - Automatic cleanup on app uninstall (via Android account system)
 * - Handles reinstalls gracefully (recreates account if needed)
 */
object GroupContactSyncManager {

    private const val MIME_TYPE_GROUP_CHAT = "vnd.android.cursor.item/vnd.com.bothbubbles.group"
    private const val AVATAR_SIZE_PX = 256

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun chatDao(): ChatDao
    }

    /**
     * Data class to hold group chat info with participants for syncing.
     */
    private data class GroupChatWithParticipants(
        val guid: String,
        val displayName: String,
        val participants: List<HandleEntity>
    )

    /**
     * Ensures the BothBubbles account exists in the system.
     * Creates it if missing (e.g., after reinstall).
     */
    fun ensureAccountExists(context: Context): Account? {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(BothBubblesAuthenticator.ACCOUNT_TYPE)

        if (accounts.isNotEmpty()) {
            return accounts[0]
        }

        // Create new account
        val account = Account(
            BothBubblesAuthenticator.ACCOUNT_NAME,
            BothBubblesAuthenticator.ACCOUNT_TYPE
        )

        return try {
            val added = accountManager.addAccountExplicitly(account, null, null)
            if (added) {
                Timber.i("Created BothBubbles account for contact sync")
                // Make contacts visible in people app
                ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
                account
            } else {
                Timber.w("Failed to create BothBubbles account")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error creating BothBubbles account")
            null
        }
    }

    /**
     * Performs a full sync of group chats to contacts.
     * Called from sync adapter or manually on app launch.
     */
    fun performSync(context: Context) {
        runBlocking {
            performSyncSuspend(context)
        }
    }

    // Scope for background sync operations
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Trigger a sync in the background.
     * Safe to call from anywhere - will not block the caller.
     *
     * Call this when:
     * - A new group chat is created or received
     * - A group chat is renamed
     * - A group chat is deleted
     * - Group chat participants change
     */
    fun triggerSync(context: Context) {
        syncScope.launch {
            try {
                performSyncSuspend(context.applicationContext)
            } catch (e: Exception) {
                Timber.w(e, "Background group contact sync failed")
            }
        }
    }

    /**
     * Performs sync asynchronously.
     */
    suspend fun performSyncSuspend(context: Context) = withContext(Dispatchers.IO) {
        val account = ensureAccountExists(context)
        if (account == null) {
            Timber.w("Cannot sync group contacts: no account")
            return@withContext
        }

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncEntryPoint::class.java
            )
            val chatDao = entryPoint.chatDao()

            // Get all group chats
            val groupChats = chatDao.getAllGroupChats()
            Timber.d("Syncing ${groupChats.size} group chats to contacts")

            if (groupChats.isEmpty()) {
                // No group chats - clean up any existing contacts
                val existingContacts = getExistingSyncedContacts(context, account)
                for (contact in existingContacts) {
                    deleteContact(context, contact.rawContactId)
                }
                return@withContext
            }

            // Get participants for all group chats
            val allGuids = groupChats.map { it.guid }
            val participantsWithGuids = chatDao.getParticipantsWithChatGuids(allGuids)
            val participantsByGuid = participantsWithGuids.groupBy(
                keySelector = { it.chatGuid },
                valueTransform = { it.handle }
            )

            // Build group chat data with participants
            val groupChatsWithParticipants = groupChats.map { chat ->
                GroupChatWithParticipants(
                    guid = chat.guid,
                    displayName = chat.displayName ?: "Group Chat",
                    participants = participantsByGuid[chat.guid] ?: emptyList()
                )
            }

            // Get existing synced contacts
            val existingContacts = getExistingSyncedContacts(context, account)
            Timber.d("Found ${existingContacts.size} existing synced contacts")

            // Build set of current group chat GUIDs
            val currentGroupGuids = groupChats.map { it.guid }.toSet()

            // Delete contacts for groups that no longer exist
            val toDelete = existingContacts.filter { it.chatGuid !in currentGroupGuids }
            for (contact in toDelete) {
                deleteContact(context, contact.rawContactId)
                Timber.d("Deleted contact for removed group: ${contact.displayName}")
            }

            // Create/update contacts for current groups
            for (groupChat in groupChatsWithParticipants) {
                val displayName = formatGroupDisplayName(groupChat.displayName)
                val existing = existingContacts.find { it.chatGuid == groupChat.guid }

                // Generate avatar bitmap
                val avatarBitmap = generateGroupAvatar(context, groupChat.participants)

                if (existing != null) {
                    // Update if name changed
                    if (existing.displayName != displayName) {
                        updateContactName(context, existing.rawContactId, displayName)
                        Timber.d("Updated contact name: ${existing.displayName} -> $displayName")
                    }
                    // Always update the photo (in case participants changed)
                    updateContactPhoto(context, existing.rawContactId, avatarBitmap)
                } else {
                    // Create new contact with photo
                    createGroupContact(context, account, groupChat.guid, displayName, avatarBitmap)
                    Timber.d("Created contact for group: $displayName")
                }

                avatarBitmap?.recycle()
            }

            Timber.i("Group contact sync completed")
        } catch (e: Exception) {
            Timber.e(e, "Error syncing group contacts")
        }
    }

    /**
     * Generate a group avatar bitmap from participants.
     */
    private fun generateGroupAvatar(context: Context, participants: List<HandleEntity>): Bitmap? {
        if (participants.isEmpty()) return null

        val names = participants.take(4).map { handle ->
            handle.cachedDisplayName ?: handle.formattedAddress ?: handle.address
        }
        val avatarPaths = participants.take(4).map { handle ->
            handle.cachedAvatarPath
        }

        return try {
            GroupAvatarRenderer.generateGroupCollageBitmapWithPhotos(
                context = context,
                names = names,
                avatarPaths = avatarPaths,
                sizePx = AVATAR_SIZE_PX,
                circleCrop = true
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to generate group avatar")
            null
        }
    }

    /**
     * Formats the display name for a group contact.
     * Format: "Group Name (BothBubbles)"
     */
    private fun formatGroupDisplayName(groupName: String): String {
        return "$groupName (BothBubbles)"
    }

    /**
     * Creates a new contact for a group chat.
     */
    private fun createGroupContact(
        context: Context,
        account: Account,
        chatGuid: String,
        displayName: String,
        avatarBitmap: Bitmap?
    ) {
        val ops = ArrayList<ContentProviderOperation>()

        // Create raw contact with aggregation mode to keep it separate
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(
                    ContactsContract.RawContacts.AGGREGATION_MODE,
                    ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED
                )
                .build()
        )

        // Add display name
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                .build()
        )

        // Add custom data row with chat GUID (for our intent handler)
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, MIME_TYPE_GROUP_CHAT)
                .withValue(ContactsContract.Data.DATA1, chatGuid)
                .withValue(ContactsContract.Data.DATA2, "Send Message")
                .withValue(ContactsContract.Data.DATA3, displayName)
                .build()
        )

        // Add photo if available
        if (avatarBitmap != null) {
            val photoData = bitmapToByteArray(avatarBitmap)
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoData)
                    .build()
            )
        }

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create contact for $displayName")
        }
    }

    /**
     * Update the photo for an existing contact.
     */
    private fun updateContactPhoto(context: Context, rawContactId: Long, avatarBitmap: Bitmap?) {
        if (avatarBitmap == null) return

        val photoData = bitmapToByteArray(avatarBitmap)
        val ops = ArrayList<ContentProviderOperation>()

        // First delete existing photo
        ops.add(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(
                        rawContactId.toString(),
                        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                    )
                )
                .build()
        )

        // Insert new photo
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoData)
                .build()
        )

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update contact photo")
        }
    }

    /**
     * Convert bitmap to byte array for contact photo.
     */
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Updates the display name of an existing contact.
     */
    private fun updateContactName(
        context: Context,
        rawContactId: Long,
        newDisplayName: String
    ) {
        val ops = ArrayList<ContentProviderOperation>()

        // Update display name
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(
                        rawContactId.toString(),
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newDisplayName)
                .build()
        )

        // Also update the custom data row
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), MIME_TYPE_GROUP_CHAT)
                )
                .withValue(ContactsContract.Data.DATA3, newDisplayName)
                .build()
        )

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update contact name")
        }
    }

    /**
     * Deletes a synced contact.
     */
    private fun deleteContact(context: Context, rawContactId: Long) {
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString())
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete contact $rawContactId")
        }
    }

    /**
     * Gets all contacts that were synced by BothBubbles.
     */
    private fun getExistingSyncedContacts(context: Context, account: Account): List<SyncedContact> {
        val contacts = mutableListOf<SyncedContact>()

        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.DATA1, // chatGuid
            ContactsContract.Data.DATA3  // displayName
        )

        val selection = """
            ${ContactsContract.Data.MIMETYPE} = ? AND
            ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND
            ${ContactsContract.RawContacts.ACCOUNT_NAME} = ?
        """.trimIndent()

        val selectionArgs = arrayOf(
            MIME_TYPE_GROUP_CHAT,
            account.type,
            account.name
        )

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val rawContactIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val data1Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val data3Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)

            while (cursor.moveToNext()) {
                contacts.add(
                    SyncedContact(
                        rawContactId = cursor.getLong(rawContactIdIdx),
                        chatGuid = cursor.getString(data1Idx) ?: "",
                        displayName = cursor.getString(data3Idx) ?: ""
                    )
                )
            }
        }

        return contacts
    }

    /**
     * Removes the BothBubbles account and all associated contacts.
     * Call this if the user wants to disable the feature.
     */
    fun removeAccount(context: Context) {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(BothBubblesAuthenticator.ACCOUNT_TYPE)

        for (account in accounts) {
            try {
                @Suppress("DEPRECATION")
                accountManager.removeAccount(account, null, null)
                Timber.i("Removed BothBubbles account")
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove BothBubbles account")
            }
        }
    }

    private data class SyncedContact(
        val rawContactId: Long,
        val chatGuid: String,
        val displayName: String
    )
}
