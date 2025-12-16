package com.bothbubbles.core.network.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Chat query request
 */
@JsonClass(generateAdapter = true)
data class ChatQueryRequest(
    @Json(name = "with") val with: List<String> = listOf("participants", "lastmessage"),
    @Json(name = "offset") val offset: Int = 0,
    @Json(name = "limit") val limit: Int = 100,
    @Json(name = "sort") val sort: String = "lastmessage"
)

/**
 * Update chat request
 */
@JsonClass(generateAdapter = true)
data class UpdateChatRequest(
    @Json(name = "displayName") val displayName: String? = null
)

/**
 * Create chat request
 */
@JsonClass(generateAdapter = true)
data class CreateChatRequest(
    @Json(name = "addresses") val addresses: List<String>,
    @Json(name = "message") val message: String? = null,
    @Json(name = "service") val service: String = "iMessage",
    @Json(name = "method") val method: String = "private-api"
)

/**
 * Send message request
 */
@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    @Json(name = "chatGuid") val chatGuid: String,
    @Json(name = "tempGuid") val tempGuid: String,
    @Json(name = "message") val message: String,
    @Json(name = "method") val method: String = "private-api",
    @Json(name = "effectId") val effectId: String? = null,
    @Json(name = "subject") val subject: String? = null,
    @Json(name = "selectedMessageGuid") val selectedMessageGuid: String? = null,
    @Json(name = "partIndex") val partIndex: Int? = null,
    @Json(name = "ddScan") val ddScan: Boolean = false
)

/**
 * Send reaction request
 */
@JsonClass(generateAdapter = true)
data class SendReactionRequest(
    @Json(name = "chatGuid") val chatGuid: String,
    @Json(name = "selectedMessageGuid") val selectedMessageGuid: String,
    @Json(name = "selectedMessageText") val selectedMessageText: String? = null,
    @Json(name = "reaction") val reaction: String, // love, like, dislike, laugh, emphasize, question
    @Json(name = "partIndex") val partIndex: Int = 0
)

/**
 * Edit message request
 */
@JsonClass(generateAdapter = true)
data class EditMessageRequest(
    @Json(name = "editedMessage") val editedMessage: String,
    @Json(name = "backwardsCompatibilityMessage") val backwardsCompatibilityMessage: String? = null,
    @Json(name = "partIndex") val partIndex: Int = 0
)

/**
 * Unsend message request
 */
@JsonClass(generateAdapter = true)
data class UnsendMessageRequest(
    @Json(name = "partIndex") val partIndex: Int = 0
)

/**
 * Handle query request
 */
@JsonClass(generateAdapter = true)
data class HandleQueryRequest(
    @Json(name = "with") val with: List<String>? = null,
    @Json(name = "address") val address: String? = null,
    @Json(name = "offset") val offset: Int = 0,
    @Json(name = "limit") val limit: Int = 100
)

/**
 * Register FCM device request
 */
@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    @Json(name = "name") val name: String,
    @Json(name = "identifier") val identifier: String
)

/**
 * Message query request - for fetching messages across all chats
 */
@JsonClass(generateAdapter = true)
data class MessageQueryRequest(
    @Json(name = "with") val with: List<String> = listOf("chat", "attachment", "handle", "attributedBody", "messageSummaryInfo"),
    @Json(name = "offset") val offset: Int = 0,
    @Json(name = "limit") val limit: Int = 100,
    @Json(name = "sort") val sort: String = "DESC",
    @Json(name = "after") val after: Long? = null,
    @Json(name = "before") val before: Long? = null
)
