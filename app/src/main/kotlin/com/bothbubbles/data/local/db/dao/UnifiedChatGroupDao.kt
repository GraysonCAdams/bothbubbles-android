package com.bothbubbles.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bothbubbles.data.local.db.entity.UnifiedChatGroupEntity
import com.bothbubbles.data.local.db.entity.UnifiedChatMember
import kotlinx.coroutines.flow.Flow

@Dao
interface UnifiedChatGroupDao {

    // ===== Queries =====

    @Query("""
        SELECT * FROM unified_chat_groups
        WHERE is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeActiveGroups(): Flow<List<UnifiedChatGroupEntity>>

    @Query("""
        SELECT * FROM unified_chat_groups
        WHERE is_archived = 0
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getActiveGroupsPaginated(limit: Int, offset: Int): List<UnifiedChatGroupEntity>

    @Query("""
        SELECT COUNT(*) FROM unified_chat_groups
        WHERE is_archived = 0
    """)
    suspend fun getActiveGroupCount(): Int

    @Query("""
        SELECT * FROM unified_chat_groups
        ORDER BY is_pinned DESC, pin_index ASC, latest_message_date DESC
    """)
    fun observeAllGroups(): Flow<List<UnifiedChatGroupEntity>>

    @Query("""
        SELECT * FROM unified_chat_groups
        WHERE is_archived = 1
        ORDER BY latest_message_date DESC
    """)
    fun observeArchivedGroups(): Flow<List<UnifiedChatGroupEntity>>

    @Query("""
        SELECT * FROM unified_chat_groups
        WHERE is_starred = 1
        ORDER BY latest_message_date DESC
    """)
    fun observeStarredGroups(): Flow<List<UnifiedChatGroupEntity>>

    @Query("SELECT * FROM unified_chat_groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: Long): UnifiedChatGroupEntity?

    @Query("SELECT * FROM unified_chat_groups WHERE id = :groupId")
    fun observeGroupById(groupId: Long): Flow<UnifiedChatGroupEntity?>

    @Query("SELECT * FROM unified_chat_groups WHERE identifier = :identifier LIMIT 1")
    suspend fun getGroupByIdentifier(identifier: String): UnifiedChatGroupEntity?

    @Query("SELECT * FROM unified_chat_groups WHERE primary_chat_guid = :chatGuid LIMIT 1")
    suspend fun getGroupByPrimaryChatGuid(chatGuid: String): UnifiedChatGroupEntity?

    /**
     * Search unified groups by display name, identifier, or handle nickname.
     * Used by the compose screen to find existing conversations with nicknames.
     * Only returns non-archived groups.
     *
     * Joins with handles table to search cached_display_name (e.g., "💛 Liz").
     */
    @Query("""
        SELECT DISTINCT ucg.* FROM unified_chat_groups ucg
        LEFT JOIN handles h ON ucg.identifier = h.address
        WHERE ucg.is_archived = 0
        AND (
            ucg.display_name LIKE '%' || :query || '%' COLLATE NOCASE
            OR ucg.identifier LIKE '%' || :query || '%' COLLATE NOCASE
            OR h.cached_display_name LIKE '%' || :query || '%' COLLATE NOCASE
        )
        ORDER BY ucg.latest_message_date DESC
        LIMIT :limit
    """)
    suspend fun searchGroups(query: String, limit: Int = 10): List<UnifiedChatGroupEntity>

    // ===== Member Queries =====

    @Query("""
        SELECT chat_guid FROM unified_chat_members
        WHERE group_id = :groupId
    """)
    suspend fun getChatGuidsForGroup(groupId: Long): List<String>

    /**
     * Batch fetch all chat GUIDs for multiple groups in a single query.
     * Returns a list of UnifiedChatMember objects with group_id and chat_guid.
     * Much more efficient than calling getChatGuidsForGroup N times.
     */
    @Query("""
        SELECT * FROM unified_chat_members
        WHERE group_id IN (:groupIds)
    """)
    suspend fun getChatGuidsForGroups(groupIds: List<Long>): List<UnifiedChatMember>

    @Query("""
        SELECT chat_guid FROM unified_chat_members
        WHERE group_id = :groupId
    """)
    fun observeChatGuidsForGroup(groupId: Long): Flow<List<String>>

    @Query("""
        SELECT ucg.* FROM unified_chat_groups ucg
        INNER JOIN unified_chat_members ucm ON ucg.id = ucm.group_id
        WHERE ucm.chat_guid = :chatGuid
        LIMIT 1
    """)
    suspend fun getGroupForChat(chatGuid: String): UnifiedChatGroupEntity?

    @Query("""
        SELECT ucg.* FROM unified_chat_groups ucg
        INNER JOIN unified_chat_members ucm ON ucg.id = ucm.group_id
        WHERE ucm.chat_guid = :chatGuid
        LIMIT 1
    """)
    fun observeGroupForChat(chatGuid: String): Flow<UnifiedChatGroupEntity?>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM unified_chat_members
            WHERE chat_guid = :chatGuid
        )
    """)
    suspend fun isChatInUnifiedGroup(chatGuid: String): Boolean

    @Query("SELECT COUNT(*) FROM unified_chat_members WHERE group_id = :groupId")
    suspend fun getMemberCount(groupId: Long): Int

    // ===== Inserts/Updates =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupIfNotExists(group: UnifiedChatGroupEntity): Long

    /**
     * Safely get or create a unified group for the given identifier.
     * Uses a transaction to avoid race conditions that could cause data loss.
     * Returns the group (either existing or newly created).
     */
    @Transaction
    suspend fun getOrCreateGroup(group: UnifiedChatGroupEntity): UnifiedChatGroupEntity {
        // First check if group already exists
        val existing = getGroupByIdentifier(group.identifier)
        if (existing != null) {
            return existing
        }

        // Try to insert - if another thread beat us, IGNORE will return -1
        val insertedId = insertGroupIfNotExists(group)

        // If insert succeeded, return the new group
        if (insertedId > 0) {
            return group.copy(id = insertedId)
        }

        // Another thread inserted first - fetch and return that one
        return getGroupByIdentifier(group.identifier)
            ?: throw IllegalStateException("Failed to get or create unified group for ${group.identifier}")
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: UnifiedChatMember)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(members: List<UnifiedChatMember>)

    @Update
    suspend fun updateGroup(group: UnifiedChatGroupEntity)

    @Query("""
        UPDATE unified_chat_groups
        SET latest_message_date = :date, latest_message_text = :text
        WHERE id = :groupId
    """)
    suspend fun updateLatestMessage(groupId: Long, date: Long, text: String?)

    /**
     * Update all cached message fields for a unified group.
     * Called when a new message arrives or message status changes.
     */
    @Query("""
        UPDATE unified_chat_groups
        SET latest_message_date = :date,
            latest_message_text = :text,
            latest_message_guid = :guid,
            latest_message_is_from_me = :isFromMe,
            latest_message_has_attachments = :hasAttachments,
            latest_message_source = :source,
            latest_message_date_delivered = :dateDelivered,
            latest_message_date_read = :dateRead,
            latest_message_error = :error
        WHERE id = :groupId
    """)
    suspend fun updateLatestMessageFull(
        groupId: Long,
        date: Long,
        text: String?,
        guid: String?,
        isFromMe: Boolean,
        hasAttachments: Boolean,
        source: String?,
        dateDelivered: Long?,
        dateRead: Long?,
        error: Int
    )

    @Query("UPDATE unified_chat_groups SET unread_count = :count WHERE id = :groupId")
    suspend fun updateUnreadCount(groupId: Long, count: Int)

    @Query("UPDATE unified_chat_groups SET unread_count = unread_count + 1 WHERE id = :groupId")
    suspend fun incrementUnreadCount(groupId: Long)

    @Query("UPDATE unified_chat_groups SET unread_count = 0 WHERE unread_count > 0")
    suspend fun markAllGroupsAsRead(): Int

    @Query("UPDATE unified_chat_groups SET display_name = :displayName WHERE id = :groupId")
    suspend fun updateDisplayName(groupId: Long, displayName: String?)

    @Query("UPDATE unified_chat_groups SET is_pinned = :isPinned, pin_index = :pinIndex WHERE id = :groupId")
    suspend fun updatePinStatus(groupId: Long, isPinned: Boolean, pinIndex: Int?)

    @Query("UPDATE unified_chat_groups SET is_archived = :isArchived WHERE id = :groupId")
    suspend fun updateArchiveStatus(groupId: Long, isArchived: Boolean)

    @Query("UPDATE unified_chat_groups SET is_starred = :isStarred WHERE id = :groupId")
    suspend fun updateStarredStatus(groupId: Long, isStarred: Boolean)

    @Query("UPDATE unified_chat_groups SET mute_type = :muteType WHERE id = :groupId")
    suspend fun updateMuteStatus(groupId: Long, muteType: String?)

    @Query("UPDATE unified_chat_groups SET snooze_until = :snoozeUntil WHERE id = :groupId")
    suspend fun updateSnoozeUntil(groupId: Long, snoozeUntil: Long?)

    @Query("UPDATE unified_chat_groups SET primary_chat_guid = :chatGuid WHERE id = :groupId")
    suspend fun updatePrimaryChatGuid(groupId: Long, chatGuid: String)

    // ===== Deletes =====

    @Query("DELETE FROM unified_chat_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query("DELETE FROM unified_chat_groups WHERE identifier = :identifier")
    suspend fun deleteGroupByIdentifier(identifier: String)

    @Query("DELETE FROM unified_chat_members WHERE group_id = :groupId AND chat_guid = :chatGuid")
    suspend fun removeMember(groupId: Long, chatGuid: String)

    @Query("DELETE FROM unified_chat_members WHERE chat_guid = :chatGuid")
    suspend fun removeChatFromAllGroups(chatGuid: String)

    @Query("DELETE FROM unified_chat_groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM unified_chat_members")
    suspend fun deleteAllMembers()

    // ===== Counts =====

    @Query("SELECT COUNT(*) FROM unified_chat_groups")
    suspend fun getGroupCount(): Int

    @Query("SELECT COUNT(*) FROM unified_chat_groups WHERE is_archived = 0")
    fun observeActiveGroupCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM unified_chat_groups WHERE is_archived = 1")
    fun observeArchivedGroupCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM unified_chat_groups WHERE is_starred = 1")
    fun observeStarredGroupCount(): Flow<Int>

    @Query("SELECT SUM(unread_count) FROM unified_chat_groups WHERE is_archived = 0")
    fun observeTotalUnreadCount(): Flow<Int?>

    // ===== Transactions =====

    @Transaction
    suspend fun createGroupWithMembers(
        group: UnifiedChatGroupEntity,
        chatGuids: List<String>
    ): Long {
        val createdGroup = getOrCreateGroup(group)
        val members = chatGuids.map { UnifiedChatMember(createdGroup.id, it) }
        insertMembers(members)
        return createdGroup.id
    }

    /**
     * Atomically get or create a unified group and add a chat as a member.
     * This ensures the group exists when inserting the member, preventing FOREIGN KEY errors.
     * Returns the group (either existing or newly created).
     */
    @Transaction
    suspend fun getOrCreateGroupAndAddMember(
        group: UnifiedChatGroupEntity,
        chatGuid: String
    ): UnifiedChatGroupEntity {
        val createdGroup = getOrCreateGroup(group)
        insertMember(UnifiedChatMember(groupId = createdGroup.id, chatGuid = chatGuid))
        return createdGroup
    }

    @Transaction
    suspend fun addChatToGroup(groupId: Long, chatGuid: String) {
        // First remove from any existing group
        removeChatFromAllGroups(chatGuid)
        // Then add to the new group
        insertMember(UnifiedChatMember(groupId, chatGuid))
    }

    @Transaction
    suspend fun deleteAllData() {
        deleteAllMembers()
        deleteAllGroups()
    }

    /**
     * Delete unified groups that have no members (orphaned groups).
     * This can happen if all chats in a group are deleted.
     */
    @Query("""
        DELETE FROM unified_chat_groups
        WHERE id NOT IN (SELECT DISTINCT group_id FROM unified_chat_members)
    """)
    suspend fun deleteOrphanedGroups(): Int

    /**
     * Clear invalid display names that contain service suffixes.
     * When display_name is null, the app falls back to formatted phone numbers.
     */
    @Query("""
        UPDATE unified_chat_groups
        SET display_name = NULL
        WHERE display_name LIKE '%(sms%)%'
           OR display_name LIKE '%(ft%)%'
    """)
    suspend fun clearInvalidDisplayNames(): Int

    // ===== Filtered Queries for Select All Feature =====

    /**
     * Get count of unified groups matching filter criteria.
     * Used for Gmail-style "Select All" to count all matching conversations.
     * Joins with the primary chat to get spam/category status.
     * Includes both pinned and non-pinned unified groups.
     */
    @Query("""
        SELECT COUNT(*) FROM unified_chat_groups ucg
        INNER JOIN chats c ON ucg.primary_chat_guid = c.guid
        WHERE ucg.is_archived = 0
        AND (
            (:includeSpam = 1 AND c.is_spam = 1)
            OR (:includeSpam = 0 AND c.is_spam = 0)
        )
        AND (:unreadOnly = 0 OR ucg.unread_count > 0)
        AND (:category IS NULL OR c.category = :category)
    """)
    suspend fun getFilteredGroupCount(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?
    ): Int

    /**
     * Get primary GUIDs of unified groups matching filter criteria (paginated).
     * Returns the primary_chat_guid which is used as the conversation identifier.
     * Used for batch operations in Gmail-style "Select All".
     * Includes both pinned and non-pinned unified groups.
     */
    @Query("""
        SELECT ucg.primary_chat_guid FROM unified_chat_groups ucg
        INNER JOIN chats c ON ucg.primary_chat_guid = c.guid
        WHERE ucg.is_archived = 0
        AND (
            (:includeSpam = 1 AND c.is_spam = 1)
            OR (:includeSpam = 0 AND c.is_spam = 0)
        )
        AND (:unreadOnly = 0 OR ucg.unread_count > 0)
        AND (:category IS NULL OR c.category = :category)
        ORDER BY ucg.is_pinned DESC, ucg.pin_index ASC, ucg.latest_message_date DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFilteredGroupGuids(
        includeSpam: Boolean,
        unreadOnly: Boolean,
        category: String?,
        limit: Int,
        offset: Int
    ): List<String>
}
