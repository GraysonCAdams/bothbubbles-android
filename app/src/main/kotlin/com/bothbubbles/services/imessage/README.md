# iMessage Availability Service

## Purpose

Check if a phone number or email is registered with iMessage. Used to determine whether to send via iMessage or fall back to SMS.

## Files

| File | Description |
|------|-------------|
| `IMessageAvailabilityService.kt` | Check iMessage registration status |

## Architecture

```
iMessage Check Flow:

Phone/Email → IMessageAvailabilityService
            → Check local cache (IMessageCacheDao)
            → If not cached: Query BlueBubbles server
            → Cache result
            → Return availability status
```

## Required Patterns

### Availability Check

```kotlin
class IMessageAvailabilityService @Inject constructor(
    private val api: BothBubblesApi,
    private val cacheDao: IMessageCacheDao
) {
    suspend fun isAvailable(address: String): Boolean {
        // Check cache first
        val cached = cacheDao.getByAddress(address)
        if (cached != null && !cached.isExpired()) {
            return cached.isAvailable
        }

        // Query server
        return try {
            val response = api.checkIMessageAvailability(address)
            val isAvailable = response.body()?.data?.available ?: false

            // Cache result
            cacheDao.upsert(IMessageCacheEntity(
                address = address,
                isAvailable = isAvailable,
                checkedAt = System.currentTimeMillis()
            ))

            isAvailable
        } catch (e: Exception) {
            // Default to true if check fails (try iMessage first)
            true
        }
    }
}
```

### Cache Expiration

```kotlin
data class IMessageCacheEntity(
    val address: String,
    val isAvailable: Boolean,
    val checkedAt: Long
) {
    fun isExpired(): Boolean {
        val expirationMs = 24 * 60 * 60 * 1000L // 24 hours
        return System.currentTimeMillis() - checkedAt > expirationMs
    }
}
```

## Best Practices

1. Cache results to reduce server load
2. Expire cache after reasonable time (24 hours)
3. Handle network errors gracefully (default to iMessage)
4. Rate limit checks for batch operations
5. Normalize phone numbers before checking
