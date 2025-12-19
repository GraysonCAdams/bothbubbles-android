package com.bothbubbles.core.network.api

import com.bothbubbles.core.network.api.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for BlueBubbles server API
 * Base URL is dynamic and set via interceptor
 */
interface BothBubblesApi {

    // ===== Server =====

    @GET("api/v1/ping")
    suspend fun ping(): Response<ApiResponse<Unit>>

    @GET("api/v1/server/info")
    suspend fun getServerInfo(): Response<ApiResponse<ServerInfoDto>>

    // ===== Chats =====

    @POST("api/v1/chat/query")
    suspend fun queryChats(
        @Body request: ChatQueryRequest
    ): Response<ApiResponse<List<ChatDto>>>

    @GET("api/v1/chat/{guid}")
    suspend fun getChat(
        @Path("guid") guid: String,
        @Query("with") with: String = "participants,lastmessage"
    ): Response<ApiResponse<ChatDto>>

    @GET("api/v1/chat/{guid}/message")
    suspend fun getChatMessages(
        @Path("guid") guid: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String = "DESC",
        @Query("with") with: String = "attachment,handle,attributedBody,messageSummaryInfo",
        @Query("before") before: Long? = null,
        @Query("after") after: Long? = null
    ): Response<ApiResponse<List<MessageDto>>>

    @POST("api/v1/chat/{guid}/read")
    suspend fun markChatRead(
        @Path("guid") guid: String
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/chat/{guid}/unread")
    suspend fun markChatUnread(
        @Path("guid") guid: String
    ): Response<ApiResponse<Unit>>

    @PUT("api/v1/chat/{guid}")
    suspend fun updateChat(
        @Path("guid") guid: String,
        @Body request: UpdateChatRequest
    ): Response<ApiResponse<ChatDto>>

    @DELETE("api/v1/chat/{guid}")
    suspend fun deleteChat(
        @Path("guid") guid: String
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/chat/new")
    suspend fun createChat(
        @Body request: CreateChatRequest
    ): Response<ApiResponse<ChatDto>>

    @GET("api/v1/chat/count")
    suspend fun getChatCount(): Response<ApiResponse<Int>>

    // ===== Message Count =====

    @GET("api/v1/message/count")
    suspend fun getMessageCount(
        @Query("after") after: Long? = null,
        @Query("before") before: Long? = null,
        @Query("chatGuid") chatGuid: String? = null
    ): Response<ApiResponse<MessageCountDto>>

    // ===== Messages =====

    @POST("api/v1/message/text")
    suspend fun sendMessage(
        @Body request: SendMessageRequest
    ): Response<ApiResponse<MessageDto>>

    @Multipart
    @POST("api/v1/message/attachment")
    suspend fun sendAttachment(
        @Part("chatGuid") chatGuid: RequestBody,
        @Part("tempGuid") tempGuid: RequestBody,
        @Part("name") name: RequestBody,
        @Part("method") method: RequestBody,
        @Part attachment: MultipartBody.Part
    ): Response<ApiResponse<MessageDto>>

    @POST("api/v1/message/react")
    suspend fun sendReaction(
        @Body request: SendReactionRequest
    ): Response<ApiResponse<MessageDto>>

    @POST("api/v1/message/{guid}/edit")
    suspend fun editMessage(
        @Path("guid") guid: String,
        @Body request: EditMessageRequest
    ): Response<ApiResponse<MessageDto>>

    @POST("api/v1/message/{guid}/unsend")
    suspend fun unsendMessage(
        @Path("guid") guid: String,
        @Body request: UnsendMessageRequest
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/message/{guid}")
    suspend fun getMessage(
        @Path("guid") guid: String,
        @Query("with") with: String = "attachment,handle"
    ): Response<ApiResponse<MessageDto>>

    @DELETE("api/v1/message/{guid}")
    suspend fun deleteMessage(
        @Path("guid") guid: String
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/message/query")
    suspend fun queryMessages(
        @Body request: MessageQueryRequest
    ): Response<ApiResponse<List<MessageDto>>>

    // ===== Attachments =====

    @GET("api/v1/attachment/{guid}")
    suspend fun getAttachment(
        @Path("guid") guid: String
    ): Response<ApiResponse<AttachmentDto>>

    @GET("api/v1/attachment/{guid}/download")
    suspend fun downloadAttachment(
        @Path("guid") guid: String,
        @Query("original") original: Boolean = true
    ): Response<okhttp3.ResponseBody>

    @GET("api/v1/attachment/{guid}/blurhash")
    suspend fun getAttachmentBlurhash(
        @Path("guid") guid: String
    ): Response<ApiResponse<String>>

    // ===== Handles =====

    @POST("api/v1/handle/query")
    suspend fun queryHandles(
        @Body request: HandleQueryRequest
    ): Response<ApiResponse<List<HandleDto>>>

    @GET("api/v1/handle/{guid}")
    suspend fun getHandle(
        @Path("guid") guid: String
    ): Response<ApiResponse<HandleDto>>

    @GET("api/v1/handle/availability/imessage")
    suspend fun checkIMessageAvailability(
        @Query("address") address: String
    ): Response<ApiResponse<IMessageAvailabilityResponse>>

    // ===== Contacts =====

    @GET("api/v1/contact")
    suspend fun getContacts(
        @Query("extraProperties") extraProperties: String = "avatar"
    ): Response<ApiResponse<List<ContactDto>>>

    // ===== FCM =====

    @POST("api/v1/fcm/device")
    suspend fun registerFcmDevice(
        @Body request: RegisterDeviceRequest
    ): Response<ApiResponse<Any?>>

    @GET("api/v1/fcm/client")
    suspend fun getFcmClient(): Response<ApiResponse<FcmClientDto>>

    // ===== FaceTime =====

    @POST("api/v1/facetime/answer/{callUuid}")
    suspend fun answerFaceTime(
        @Path("callUuid") callUuid: String
    ): Response<ApiResponse<FaceTimeLinkDto>>

    @POST("api/v1/facetime/leave/{callUuid}")
    suspend fun leaveFaceTime(
        @Path("callUuid") callUuid: String
    ): Response<ApiResponse<Unit>>

    // ===== iCloud =====

    @GET("api/v1/icloud/account")
    suspend fun getICloudAccountInfo(): Response<ApiResponse<ICloudAccountInfoDto>>

    // ===== Find My =====

    @GET("api/v1/icloud/findmy/devices")
    suspend fun getFindMyDevices(): Response<ApiResponse<List<FindMyDeviceDto>>>

    @POST("api/v1/icloud/findmy/devices/refresh")
    suspend fun refreshFindMyDevices(): Response<ApiResponse<List<FindMyDeviceDto>>>

    @GET("api/v1/icloud/findmy/friends")
    suspend fun getFindMyFriends(): Response<ApiResponse<List<FindMyFriendDto>>>

    @POST("api/v1/icloud/findmy/friends/refresh")
    suspend fun refreshFindMyFriends(): Response<ApiResponse<List<FindMyFriendDto>>>
}
