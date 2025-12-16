# API Layer

## Purpose

Retrofit API interfaces and supporting classes for communicating with the BlueBubbles server and external services.

## Files

| File | Description |
|------|-------------|
| `BothBubblesApi.kt` | Main BlueBubbles server API interface |
| `TenorApi.kt` | Tenor GIF search API |
| `AuthInterceptor.kt` | OkHttp interceptor for auth headers |
| `ProgressRequestBody.kt` | RequestBody wrapper for upload progress callbacks |

## Architecture

```
NetworkModule provides:
├── OkHttpClient (with AuthInterceptor)
├── Retrofit instance
├── BothBubblesApi
└── TenorApi
```

## Required Patterns

### API Endpoints

```kotlin
interface BothBubblesApi {
    // GET requests
    @GET("api/v1/chat/{guid}/messages")
    suspend fun getMessages(
        @Path("guid") chatGuid: String,
        @Query("limit") limit: Int = 25
    ): Response<MessageListResponse>

    // POST requests with body
    @POST("api/v1/message/text")
    suspend fun sendMessage(
        @Body request: SendMessageRequest
    ): Response<MessageResponse>

    // Multipart uploads
    @Multipart
    @POST("api/v1/attachment")
    suspend fun uploadAttachment(
        @Part file: MultipartBody.Part
    ): Response<AttachmentResponse>
}
```

### Authentication

`AuthInterceptor` adds auth headers to all requests:

```kotlin
class AuthInterceptor @Inject constructor(
    private val serverPreferences: ServerPreferences
) : Interceptor {
    override fun intercept(chain: Chain): Response {
        val password = runBlocking { serverPreferences.password.first() }
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Basic $password")
            .build()
        return chain.proceed(request)
    }
}
```

### Upload Progress

Use `ProgressRequestBody` for upload progress tracking:

```kotlin
val progressBody = ProgressRequestBody(requestBody) { progress ->
    // Update UI with progress (0.0 to 1.0)
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `dto/` | Data Transfer Objects for requests/responses |

## Best Practices

1. Use `suspend` functions for all API calls
2. Return `Response<T>` to allow error handling in repositories
3. Use `@Path`, `@Query`, `@Body` annotations appropriately
4. Keep DTOs separate from domain models
5. Handle network errors in repositories, not API layer
