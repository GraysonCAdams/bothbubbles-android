# Remote Data

## Purpose

Remote data access layer for communicating with the BlueBubbles server and external APIs. Uses Retrofit for HTTP requests.

## Architecture

```
remote/
├── api/
│   ├── BothBubblesApi.kt      # Main API interface
│   ├── TenorApi.kt            # GIF search API
│   ├── AuthInterceptor.kt     # Auth header injection
│   ├── ProgressRequestBody.kt # Upload progress tracking
│   └── dto/                   # Data Transfer Objects
│       ├── ApiDtos.kt         # Response DTOs
│       └── RequestDtos.kt     # Request DTOs
```

## Required Patterns

### API Interface

```kotlin
interface BothBubblesApi {
    @GET("api/v1/chat")
    suspend fun getChats(): Response<ChatListResponse>

    @POST("api/v1/message/text")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<MessageResponse>
}
```

### Error Handling

Always handle API errors in repositories:

```kotlin
suspend fun fetchChats(): Result<List<Chat>> {
    return try {
        val response = api.getChats()
        if (response.isSuccessful) {
            Result.success(response.body()!!.data.map { it.toChat() })
        } else {
            Result.failure(NetworkError.ServerError(response.code()))
        }
    } catch (e: IOException) {
        Result.failure(NetworkError.NoConnection)
    }
}
```

## Sub-packages

| Package | Purpose |
|---------|---------|
| `api/` | Retrofit interfaces, interceptors, and DTOs |
