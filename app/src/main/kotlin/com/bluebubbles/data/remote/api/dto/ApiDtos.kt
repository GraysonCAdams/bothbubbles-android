package com.bluebubbles.data.remote.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Generic API response wrapper
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "status") val status: Int,
    @Json(name = "message") val message: String? = null,
    @Json(name = "error") val error: ApiError? = null,
    @Json(name = "data") val data: T? = null
)

@JsonClass(generateAdapter = true)
data class ApiError(
    @Json(name = "type") val type: String? = null,
    @Json(name = "message") val message: String? = null
)

/**
 * Server info
 */
@JsonClass(generateAdapter = true)
data class ServerInfoDto(
    @Json(name = "os_version") val osVersion: String? = null,
    @Json(name = "server_version") val serverVersion: String? = null,
    @Json(name = "private_api") val privateApi: Boolean = false,
    @Json(name = "proxy_service") val proxyService: String? = null,
    @Json(name = "helper_connected") val helperConnected: Boolean = false,
    @Json(name = "detected_icloud") val detectedIcloud: String? = null
)

/**
 * Chat DTO
 */
@JsonClass(generateAdapter = true)
data class ChatDto(
    @Json(name = "guid") val guid: String,
    @Json(name = "chatIdentifier") val chatIdentifier: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "participants") val participants: List<HandleDto>? = null,
    @Json(name = "lastMessage") val lastMessage: MessageDto? = null,
    @Json(name = "style") val style: Int? = null,
    @Json(name = "isArchived") val isArchived: Boolean = false,
    @Json(name = "isPinned") val isPinned: Boolean = false,
    @Json(name = "hasUnreadMessage") val hasUnreadMessage: Boolean = false
)

/**
 * Message DTO
 */
@JsonClass(generateAdapter = true)
data class MessageDto(
    @Json(name = "guid") val guid: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "subject") val subject: String? = null,
    @Json(name = "dateCreated") val dateCreated: Long? = null,
    @Json(name = "dateRead") val dateRead: Long? = null,
    @Json(name = "dateDelivered") val dateDelivered: Long? = null,
    @Json(name = "dateEdited") val dateEdited: Long? = null,
    @Json(name = "datePlayed") val datePlayed: Long? = null,
    @Json(name = "isFromMe") val isFromMe: Boolean = false,
    @Json(name = "handleId") val handleId: Long? = null,
    @Json(name = "error") val error: Int = 0,
    @Json(name = "itemType") val itemType: Int = 0,
    @Json(name = "groupTitle") val groupTitle: String? = null,
    @Json(name = "groupActionType") val groupActionType: Int = 0,
    @Json(name = "balloonBundleId") val balloonBundleId: String? = null,
    @Json(name = "associatedMessageGuid") val associatedMessageGuid: String? = null,
    @Json(name = "associatedMessagePart") val associatedMessagePart: Int? = null,
    @Json(name = "associatedMessageType") val associatedMessageType: String? = null,
    @Json(name = "expressiveSendStyleId") val expressiveSendStyleId: String? = null,
    @Json(name = "threadOriginatorGuid") val threadOriginatorGuid: String? = null,
    @Json(name = "threadOriginatorPart") val threadOriginatorPart: String? = null,
    @Json(name = "hasAttachments") val hasAttachments: Boolean = false,
    @Json(name = "hasReactions") val hasReactions: Boolean = false,
    @Json(name = "bigEmoji") val bigEmoji: Boolean = false,
    @Json(name = "wasDeliveredQuietly") val wasDeliveredQuietly: Boolean = false,
    @Json(name = "didNotifyRecipient") val didNotifyRecipient: Boolean = false,
    @Json(name = "attachments") val attachments: List<AttachmentDto>? = null,
    @Json(name = "handle") val handle: HandleDto? = null,
    @Json(name = "chats") val chats: List<ChatDto>? = null
)

/**
 * Handle DTO
 */
@JsonClass(generateAdapter = true)
data class HandleDto(
    @Json(name = "originalROWID") val originalRowId: Int? = null,
    @Json(name = "address") val address: String,
    @Json(name = "service") val service: String = "iMessage",
    @Json(name = "country") val country: String? = null,
    @Json(name = "formattedAddress") val formattedAddress: String? = null,
    @Json(name = "defaultEmail") val defaultEmail: String? = null,
    @Json(name = "defaultPhone") val defaultPhone: String? = null
)

/**
 * Attachment DTO
 */
@JsonClass(generateAdapter = true)
data class AttachmentDto(
    @Json(name = "guid") val guid: String,
    @Json(name = "originalROWID") val originalRowId: Int? = null,
    @Json(name = "uti") val uti: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "isOutgoing") val isOutgoing: Boolean = false,
    @Json(name = "transferName") val transferName: String? = null,
    @Json(name = "totalBytes") val totalBytes: Long? = null,
    @Json(name = "height") val height: Int? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "hasLivePhoto") val hasLivePhoto: Boolean = false,
    @Json(name = "hideAttachment") val hideAttachment: Boolean = false,
    @Json(name = "isSticker") val isSticker: Boolean = false,
    @Json(name = "metadata") val metadata: Map<String, Any>? = null
)

/**
 * Contact DTO
 */
@JsonClass(generateAdapter = true)
data class ContactDto(
    @Json(name = "firstName") val firstName: String? = null,
    @Json(name = "lastName") val lastName: String? = null,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "phoneNumbers") val phoneNumbers: List<String>? = null,
    @Json(name = "emails") val emails: List<String>? = null,
    @Json(name = "avatar") val avatar: String? = null
)

/**
 * FCM Client DTO
 */
@JsonClass(generateAdapter = true)
data class FcmClientDto(
    @Json(name = "projectNumber") val projectNumber: String? = null,
    @Json(name = "appId") val appId: String? = null,
    @Json(name = "apiKey") val apiKey: String? = null,
    @Json(name = "storageBucket") val storageBucket: String? = null
)

/**
 * FaceTime Link DTO - returned when answering a FaceTime call
 */
@JsonClass(generateAdapter = true)
data class FaceTimeLinkDto(
    @Json(name = "link") val link: String
)
