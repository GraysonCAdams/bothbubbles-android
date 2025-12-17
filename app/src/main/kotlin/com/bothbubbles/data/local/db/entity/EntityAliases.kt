@file:Suppress("unused")

package com.bothbubbles.data.local.db.entity

import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Type aliases for backward compatibility with existing code.
 *
 * The canonical entity definitions are now in :core:model module at:
 * com.bothbubbles.core.model.entity
 *
 * New code should import directly from com.bothbubbles.core.model.entity.
 * These aliases allow existing code to continue working during the migration period.
 *
 * @see com.bothbubbles.core.model.entity for the canonical definitions
 */

// Primary entities
typealias ChatEntity = com.bothbubbles.core.model.entity.ChatEntity
typealias MessageEntity = com.bothbubbles.core.model.entity.MessageEntity
typealias AttachmentEntity = com.bothbubbles.core.model.entity.AttachmentEntity
typealias HandleEntity = com.bothbubbles.core.model.entity.HandleEntity

// Cross-reference and junction tables
typealias ChatHandleCrossRef = com.bothbubbles.core.model.entity.ChatHandleCrossRef
typealias UnifiedChatGroupEntity = com.bothbubbles.core.model.entity.UnifiedChatGroupEntity
typealias UnifiedChatMember = com.bothbubbles.core.model.entity.UnifiedChatMember

// Pending message queue
typealias PendingMessageEntity = com.bothbubbles.core.model.entity.PendingMessageEntity
typealias PendingAttachmentEntity = com.bothbubbles.core.model.entity.PendingAttachmentEntity
typealias PendingSyncStatus = com.bothbubbles.core.model.entity.PendingSyncStatus

// Supporting entities
typealias SyncRangeEntity = com.bothbubbles.core.model.entity.SyncRangeEntity
typealias SyncSource = com.bothbubbles.core.model.entity.SyncSource
typealias LinkPreviewEntity = com.bothbubbles.core.model.entity.LinkPreviewEntity
typealias LinkPreviewFetchStatus = com.bothbubbles.core.model.entity.LinkPreviewFetchStatus
typealias ScheduledMessageEntity = com.bothbubbles.core.model.entity.ScheduledMessageEntity
typealias ScheduledMessageStatus = com.bothbubbles.core.model.entity.ScheduledMessageStatus
typealias SeenMessageEntity = com.bothbubbles.core.model.entity.SeenMessageEntity
typealias QuickReplyTemplateEntity = com.bothbubbles.core.model.entity.QuickReplyTemplateEntity
typealias IMessageAvailabilityCacheEntity = com.bothbubbles.core.model.entity.IMessageAvailabilityCacheEntity
typealias AutoRespondedSenderEntity = com.bothbubbles.core.model.entity.AutoRespondedSenderEntity
typealias VerifiedCounterpartCheckEntity = com.bothbubbles.core.model.entity.VerifiedCounterpartCheckEntity
typealias AutoShareContactEntity = com.bothbubbles.core.model.entity.AutoShareContactEntity
typealias TombstoneEntity = com.bothbubbles.core.model.entity.TombstoneEntity

// Enums and utility types
typealias MessageSource = com.bothbubbles.core.model.entity.MessageSource
typealias TransferState = com.bothbubbles.core.model.entity.TransferState
typealias ReactionClassifier = com.bothbubbles.core.model.entity.ReactionClassifier

// MessageSourceResolver is an object, create a wrapper
typealias MessageSourceResolver = com.bothbubbles.core.model.entity.MessageSourceResolver

// Error state
typealias AttachmentErrorState = com.bothbubbles.core.model.entity.AttachmentErrorState

// Nested types that need explicit aliases
typealias CheckResult = com.bothbubbles.core.model.entity.IMessageAvailabilityCacheEntity.CheckResult
typealias Tapback = com.bothbubbles.core.model.entity.ReactionClassifier.Tapback

// HandleEntity extension properties that depend on PhoneNumberFormatter
/**
 * Display name with priority: saved contact > "Maybe: inferred" > formatted address > formatted raw address.
 * The final fallback formats the address to strip service suffixes and pretty-print phone numbers.
 */
val HandleEntity.displayName: String
    get() = cachedDisplayName
        ?: inferredName?.let { "Maybe: $it" }
        ?: formattedAddress
        ?: PhoneNumberFormatter.format(address)

/**
 * Raw display name WITHOUT "Maybe:" prefix - use for contact cards, intents, and avatars.
 * The final fallback formats the address to strip service suffixes and pretty-print phone numbers.
 */
val HandleEntity.rawDisplayName: String
    get() = cachedDisplayName
        ?: inferredName
        ?: formattedAddress
        ?: PhoneNumberFormatter.format(address)
