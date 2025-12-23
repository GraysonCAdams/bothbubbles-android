package com.bothbubbles.services.contacts.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.provider.ContactsContract
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.dao.UnifiedChatDao
import com.bothbubbles.data.local.db.entity.HandleEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatEntity
import com.bothbubbles.util.GroupAvatarRenderer
import com.bothbubbles.util.UnifiedChatIdGenerator
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
        fun unifiedChatDao(): UnifiedChatDao
    }

    /**
     * Data class to hold group chat info with participants for syncing.
     * Uses unified chat ID for clean identifiers in contacts and deep links.
     */
    private data class GroupChatWithParticipants(
        val chatGuid: String,
        val unifiedChatId: String,
        val displayName: String,
        val effectiveGroupPhotoPath: String?,
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
     * Generic/default group names that should not be synced to contacts.
     * These are not useful for voice commands since they're not unique.
     */
    private val GENERIC_GROUP_NAMES = setOf(
        "group chat",
        "group",
        "unnamed group",
        "new group",
        "imessage group",
        "mms group",
        "group message",
        "group text"
    )

    /**
     * Checks if a group name is considered "named" (not generic/default).
     * Only named groups should be synced to contacts for voice commands.
     */
    private fun isNamedGroup(displayName: String?): Boolean {
        if (displayName.isNullOrBlank()) return false
        val normalized = displayName.trim().lowercase()
        return normalized !in GENERIC_GROUP_NAMES
    }

    /**
     * Checks if a group chat has had recent activity (within the last year).
     * Inactive groups are not useful for voice commands.
     */
    private fun isRecentlyActive(lastMessageDate: Long?): Boolean {
        if (lastMessageDate == null || lastMessageDate == 0L) return false
        val oneYearAgo = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000)
        return lastMessageDate > oneYearAgo
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
            val unifiedChatDao = entryPoint.unifiedChatDao()

            // Get all group chats
            val allGroupChats = chatDao.getAllGroupChats()

            // Filter to only named groups that are recently active (within 1 year)
            val eligibleGroupChats = allGroupChats.filter { chat ->
                isNamedGroup(chat.displayName) &&
                isRecentlyActive(chat.latestMessageDate)
            }

            // Deduplicate by display name (same group can have iMessage/SMS/MMS variants)
            // Keep the most recently active chat for each unique display name
            val deduplicatedGroupChats = eligibleGroupChats
                .groupBy { it.displayName?.lowercase()?.trim() }
                .mapNotNull { (_, chats) ->
                    // Pick the one with most recent activity (or first if all null)
                    chats.maxByOrNull { it.latestMessageDate ?: 0L }
                }

            val genericCount = allGroupChats.count { !isNamedGroup(it.displayName) }
            val inactiveCount = allGroupChats.count { isNamedGroup(it.displayName) && !isRecentlyActive(it.latestMessageDate) }
            val deduplicatedCount = eligibleGroupChats.size - deduplicatedGroupChats.size
            Timber.d("Syncing ${deduplicatedGroupChats.size} group chats to contacts ($genericCount generic/unnamed, $inactiveCount inactive >1yr, $deduplicatedCount duplicates excluded)")

            // Get existing synced contacts
            val existingContacts = getExistingSyncedContacts(context, account)
            Timber.d("Found ${existingContacts.size} existing synced contacts")

            if (deduplicatedGroupChats.isEmpty()) {
                // No eligible group chats - clean up all existing contacts
                for (contact in existingContacts) {
                    deleteContact(context, contact.rawContactId)
                    Timber.d("Deleted contact (no eligible groups): ${contact.displayName}")
                }
                return@withContext
            }

            // Get participants for deduplicated group chats
            val allGuids = deduplicatedGroupChats.map { it.guid }
            val participantsWithGuids = chatDao.getParticipantsWithChatGuids(allGuids)
            val participantsByGuid = participantsWithGuids.groupBy(
                keySelector = { it.chatGuid },
                valueTransform = { it.handle }
            )

            // Build group chat data with participants, ensuring each has a unified chat ID
            // This also handles migration for existing groups that don't have unified IDs yet
            val groupChatsWithParticipants = deduplicatedGroupChats.mapNotNull { chat ->
                // Resolve or create unified chat ID for this group
                val unifiedChatId = chat.unifiedChatId ?: run {
                    // Migration: create unified chat entry for existing group without one
                    val newUnifiedChat = unifiedChatDao.getOrCreate(
                        UnifiedChatEntity(
                            id = UnifiedChatIdGenerator.generate(),
                            normalizedAddress = chat.guid, // Use GUID as address for groups
                            sourceId = chat.guid
                        )
                    )
                    // Update the chat with the new unified chat ID
                    chatDao.setUnifiedChatId(chat.guid, newUnifiedChat.id)
                    Timber.d("Created unified chat ID for group: ${chat.displayName} -> ${newUnifiedChat.id}")
                    newUnifiedChat.id
                }

                GroupChatWithParticipants(
                    chatGuid = chat.guid,
                    unifiedChatId = unifiedChatId,
                    displayName = chat.displayName ?: "Group Chat",
                    effectiveGroupPhotoPath = chat.serverGroupPhotoPath,
                    participants = participantsByGuid[chat.guid] ?: emptyList()
                )
            }

            // Build lookup maps for matching existing contacts
            // We match by both unifiedChatId (new) and chatGuid (legacy migration)
            val currentUnifiedIds = groupChatsWithParticipants.map { it.unifiedChatId }.toSet()
            val currentChatGuids = groupChatsWithParticipants.map { it.chatGuid }.toSet()

            // Delete contacts for groups that no longer exist
            // Match against both unified IDs and chat GUIDs to handle migration
            val toDelete = existingContacts.filter { contact ->
                contact.identifier !in currentUnifiedIds && contact.identifier !in currentChatGuids
            }
            for (contact in toDelete) {
                deleteContact(context, contact.rawContactId)
                Timber.d("Deleted contact for ineligible group: ${contact.displayName}")
            }

            // Create/update contacts for current eligible groups
            for (groupChat in groupChatsWithParticipants) {
                val displayName = formatGroupDisplayName(groupChat.displayName)
                // Find existing contact by either unifiedChatId (new) or chatGuid (legacy migration)
                val existing = existingContacts.find {
                    it.identifier == groupChat.unifiedChatId || it.identifier == groupChat.chatGuid
                }

                // Generate avatar bitmap from server photo or participant collage
                val avatarBitmap = generateGroupAvatar(
                    context,
                    groupChat.effectiveGroupPhotoPath,
                    groupChat.participants
                )

                if (existing != null) {
                    // Update if name changed
                    if (existing.displayName != displayName) {
                        updateContactName(context, existing.rawContactId, displayName, groupChat.unifiedChatId)
                        Timber.d("Updated contact name: ${existing.displayName} -> $displayName")
                    }
                    // Always update the photo (in case participants or server photo changed)
                    updateContactPhoto(context, existing.rawContactId, avatarBitmap)
                    // Ensure IM row has correct unified chat ID (migrates legacy chatGuid to unifiedChatId)
                    ensureImRowExists(context, existing.rawContactId, groupChat.unifiedChatId)
                    // Ensure custom data row has correct unified chat ID
                    ensureCustomDataRowUpdated(context, existing.rawContactId, groupChat.unifiedChatId)
                } else {
                    // Create new contact with photo
                    createGroupContact(context, account, groupChat.unifiedChatId, displayName, avatarBitmap)
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
     * Generate a group avatar bitmap.
     * Priority: effectiveGroupPhotoPath (custom/server photo) > participant collage
     */
    private fun generateGroupAvatar(
        context: Context,
        effectiveGroupPhotoPath: String?,
        participants: List<HandleEntity>
    ): Bitmap? {
        // First try to load the chat's own photo (custom or server-downloaded)
        if (effectiveGroupPhotoPath != null) {
            try {
                val photoFile = java.io.File(effectiveGroupPhotoPath)
                if (photoFile.exists()) {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(effectiveGroupPhotoPath, options)

                    // Calculate sample size for efficient loading
                    val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, AVATAR_SIZE_PX)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize

                    val bitmap = android.graphics.BitmapFactory.decodeFile(effectiveGroupPhotoPath, options)
                    if (bitmap != null) {
                        // Scale and crop to circle
                        val scaled = Bitmap.createScaledBitmap(bitmap, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)
                        if (scaled != bitmap) bitmap.recycle()
                        Timber.d("Loaded group photo from: $effectiveGroupPhotoPath")
                        return createCircularBitmap(scaled)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load group photo from $effectiveGroupPhotoPath, falling back to collage")
            }
        }

        // Fall back to participant collage
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
            Timber.w(e, "Failed to generate group avatar collage")
            null
        }
    }

    /**
     * Calculate optimal sample size for bitmap loading.
     */
    private fun calculateSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > targetSize * 2 || height / sampleSize > targetSize * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Create a circular bitmap from a square/rectangular bitmap.
     */
    private fun createCircularBitmap(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f

        // Draw circular mask
        canvas.drawCircle(center, center, center, paint)

        // Draw source clipped to circle
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)

        // Center-crop if not square
        val srcLeft = (source.width - size) / 2
        val srcTop = (source.height - size) / 2
        canvas.drawBitmap(
            source,
            android.graphics.Rect(srcLeft, srcTop, srcLeft + size, srcTop + size),
            android.graphics.Rect(0, 0, size, size),
            paint
        )

        if (output != source) source.recycle()
        return output
    }

    /**
     * Formats the display name for a group contact.
     * Uses the group name as-is for cleaner display in Contacts app.
     */
    private fun formatGroupDisplayName(groupName: String): String {
        return groupName
    }

    /**
     * Creates a new contact for a group chat.
     *
     * @param unifiedChatId The unified chat ID - clean, URL-safe identifier for deep linking
     */
    private fun createGroupContact(
        context: Context,
        account: Account,
        unifiedChatId: String,
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

        // Add custom data row with unified chat ID (for our intent handler when tapped in Contacts app)
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, MIME_TYPE_GROUP_CHAT)
                .withValue(ContactsContract.Data.DATA1, unifiedChatId)
                .withValue(ContactsContract.Data.DATA2, "Send Message")
                .withValue(ContactsContract.Data.DATA3, displayName)
                .build()
        )

        // Add IM data row - this is what Google Assistant uses to identify messageable contacts
        // Without this, Google Assistant won't recognize these contacts for "send a message to X" commands
        // We store the unified chat ID which is URL-safe and clean for deep linking
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Im.DATA, unifiedChatId)
                .withValue(
                    ContactsContract.CommonDataKinds.Im.PROTOCOL,
                    ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM
                )
                .withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, "BothBubbles")
                .withValue(ContactsContract.CommonDataKinds.Im.TYPE, ContactsContract.CommonDataKinds.Im.TYPE_OTHER)
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
     * Ensures an IM data row exists for a contact with the correct unified chat ID.
     * This is needed for Google Assistant to recognize the contact as "messageable".
     */
    private fun ensureImRowExists(context: Context, rawContactId: Long, unifiedChatId: String) {
        // Check if IM row already exists with correct unified chat ID
        val existingImData = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Im.DATA),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(
                rawContactId.toString(),
                ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(1) // IM.DATA value
            } else null
        }

        // If exists with correct ID, nothing to do
        if (existingImData == unifiedChatId) return

        // Delete existing IM row if it has wrong data (migrates legacy chatGuid to unifiedChatId)
        if (existingImData != null) {
            context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE
                )
            )
            Timber.d("Deleted old IM row for contact $rawContactId (migrating to unifiedChatId)")
        }

        // Add IM row with unified chat ID
        try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Im.DATA, unifiedChatId)
                    .withValue(
                        ContactsContract.CommonDataKinds.Im.PROTOCOL,
                        ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM
                    )
                    .withValue(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL, "BothBubbles")
                    .withValue(ContactsContract.CommonDataKinds.Im.TYPE, ContactsContract.CommonDataKinds.Im.TYPE_OTHER)
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Timber.d("Added/updated IM row for contact $rawContactId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to add IM row for contact $rawContactId")
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
        newDisplayName: String,
        unifiedChatId: String
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

        // Also update the custom data row (DATA1 = unifiedChatId, DATA3 = displayName)
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), MIME_TYPE_GROUP_CHAT)
                )
                .withValue(ContactsContract.Data.DATA1, unifiedChatId)
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
     * Ensures the custom data row (MIME_TYPE_GROUP_CHAT) has the correct unified chat ID.
     */
    private fun ensureCustomDataRowUpdated(context: Context, rawContactId: Long, unifiedChatId: String) {
        // Check current DATA1 value
        val currentData1 = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), MIME_TYPE_GROUP_CHAT),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

        // If already correct, nothing to do
        if (currentData1 == unifiedChatId) return

        // Update DATA1 to unified chat ID (migrates legacy chatGuid to unifiedChatId)
        try {
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                android.content.ContentValues().apply {
                    put(ContactsContract.Data.DATA1, unifiedChatId)
                },
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), MIME_TYPE_GROUP_CHAT)
            )
            Timber.d("Updated custom data row for contact $rawContactId (migrated to unifiedChatId)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update custom data row for contact $rawContactId")
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
            ContactsContract.Data.DATA1, // identifier (unifiedChatId or legacy chatGuid)
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
                        identifier = cursor.getString(data1Idx) ?: "",
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

    /**
     * Represents an existing synced contact.
     * The identifier can be either a unified chat ID (new format) or chat GUID (legacy).
     */
    private data class SyncedContact(
        val rawContactId: Long,
        val identifier: String,  // unifiedChatId (new) or chatGuid (legacy, for migration)
        val displayName: String
    )
}
