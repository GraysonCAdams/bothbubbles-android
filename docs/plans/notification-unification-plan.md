# Notification System Unification Plan

## Problem Statement

The notification system has four entry points (Socket, FCM, MediaUpdater, LinkPreviewUpdater) that each build `MessageNotificationParams` independently. This leads to:

1. **Contact caching inconsistency**: Socket caches contact lookups (5-min TTL), FCM does fresh lookups every time
2. **Missing `senderHasContactInfo`**: FCM, MediaUpdater, and LinkPreviewUpdater don't pass this flag, causing incorrect business icon detection
3. **Missing `participantHasContactInfo`**: Same issue for group chat participant avatars
4. **Link preview inconsistency**: Socket fetches link previews for initial notification, FCM doesn't
5. **Wasted shortcut creation**: Bubble feature is disabled but shortcuts are still created

## Unified Vision

**Extract notification parameter building into a single, shared service that all paths use.** This ensures consistent behavior regardless of how the message arrived.

```
Before:
┌─────────┐     ┌──────────────────────────────────────────┐
│ Socket  │────>│ Build params (contact lookup, link preview)│──>NotificationService
└─────────┘     └──────────────────────────────────────────┘
┌─────────┐     ┌──────────────────────────────────────────┐
│  FCM    │────>│ Build params (different logic)           │──>NotificationService
└─────────┘     └──────────────────────────────────────────┘
┌─────────┐     ┌──────────────────────────────────────────┐
│ Media   │────>│ Build params (missing fields)            │──>NotificationService
└─────────┘     └──────────────────────────────────────────┘
┌─────────┐     ┌──────────────────────────────────────────┐
│LinkPrev │────>│ Build params (missing fields)            │──>NotificationService
└─────────┘     └──────────────────────────────────────────┘

After:
┌─────────┐
│ Socket  │──┐
└─────────┘  │
┌─────────┐  │  ┌─────────────────────────────────────┐
│  FCM    │──┼─>│ NotificationParamsBuilder (shared)  │──>NotificationService
└─────────┘  │  │ - Cached contact lookups            │
┌─────────┐  │  │ - Consistent hasContactInfo         │
│ Media   │──┤  │ - Link preview integration          │
└─────────┘  │  └─────────────────────────────────────┘
┌─────────┐  │
│LinkPrev │──┘
└─────────┘
```

---

## Implementation Plan

### Phase 1: Create NotificationParamsBuilder Service

Create a new service that encapsulates all notification parameter building logic.

**New file: `services/notifications/NotificationParamsBuilder.kt`**

```kotlin
@Singleton
class NotificationParamsBuilder @Inject constructor(
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val unifiedChatDao: UnifiedChatDao,
    private val chatRepository: ChatRepository,
    private val linkPreviewRepository: LinkPreviewRepository,
    private val androidContactsService: AndroidContactsService,
    private val displayNameResolver: DisplayNameResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    // Cached contact lookups (extracted from MessageEventHandler)
    private data class CachedContactInfo(
        val displayName: String?,
        val avatarUri: String?,
        val hasContactInfo: Boolean,
        val timestamp: Long
    )

    private val contactCacheMutex = Mutex()
    private val contactCache = mutableMapOf<String, CachedContactInfo>()
    private val lookupInProgress = mutableSetOf<String>()

    /**
     * Build notification params from a message GUID.
     * Single source of truth for all notification paths.
     */
    suspend fun buildParams(
        messageGuid: String,
        chatGuid: String,
        // Optional overrides for when caller has data already
        messageText: String? = null,
        subject: String? = null,
        senderAddress: String? = null,
        attachmentUri: Uri? = null,
        attachmentMimeType: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null,
        fetchLinkPreview: Boolean = true
    ): MessageNotificationParams? {
        // Implementation details below...
    }

    /**
     * Resolve sender info with caching (shared across all paths).
     */
    suspend fun resolveSenderInfo(address: String): SenderInfo {
        // Cached contact lookup logic
    }
}

data class SenderInfo(
    val name: String?,
    val avatarUri: String?,
    val hasContactInfo: Boolean
)
```

**Key responsibilities:**
1. **Contact caching**: Reuse the caching pattern from MessageEventHandler
2. **Chat/participant fetching**: Single implementation for all paths
3. **Link preview fetching**: Optional, controlled by parameter
4. **hasContactInfo resolution**: Always populated correctly

---

### Phase 2: Refactor Socket Path (MessageEventHandler)

**File: `services/socket/handlers/MessageEventHandler.kt`**

Changes:
1. Remove duplicate `SenderInfo` data class (use shared one)
2. Remove duplicate `lookupContactWithCache()` (use builder)
3. Delegate to `NotificationParamsBuilder.buildParams()`

```kotlin
// Before (lines 212-232)
notificationService.showMessageNotification(
    MessageNotificationParams(
        chatGuid = event.chatGuid,
        chatTitle = chatTitle,
        messageText = notificationText,
        // ... 15+ parameters built inline
    )
)

// After
val params = notificationParamsBuilder.buildParams(
    messageGuid = savedMessage.guid,
    chatGuid = event.chatGuid,
    messageText = notificationText,
    subject = savedMessage.subject,
    senderAddress = event.message.handle?.address,
    fetchLinkPreview = !isInvisibleInk
) ?: return

notificationService.showMessageNotification(params)
```

---

### Phase 3: Refactor FCM Path (FcmMessageHandler)

**File: `services/fcm/FcmMessageHandler.kt`**

Changes:
1. Remove duplicate `resolveSenderNameAndAvatar()` method
2. Use `NotificationParamsBuilder` for consistent caching
3. Add link preview fetching to match socket behavior

```kotlin
// Before (lines 258-274) - builds params inline, no caching

// After
val params = notificationParamsBuilder.buildParams(
    messageGuid = messageGuid,
    chatGuid = chatGuid,
    messageText = notificationText,
    subject = messageSubject,
    senderAddress = senderAddress,
    fetchLinkPreview = !isInvisibleInk
) ?: return

notificationService.showMessageNotification(params)
```

---

### Phase 4: Refactor NotificationMediaUpdater

**File: `services/notifications/NotificationMediaUpdater.kt`**

Changes:
1. Use `NotificationParamsBuilder` instead of building params inline
2. Pass attachment URI/MIME type as overrides

```kotlin
// Before (lines 225-243) - builds all params, missing hasContactInfo

// After
val params = notificationParamsBuilder.buildParams(
    messageGuid = message.guid,
    chatGuid = chatGuid,
    messageText = messageText,
    subject = message.subject,
    senderAddress = message.senderAddress,
    attachmentUri = attachmentUri,
    attachmentMimeType = notificationMimeType,
    fetchLinkPreview = false  // Already have attachment
) ?: return

notificationService.showMessageNotification(params)
```

---

### Phase 5: Refactor NotificationLinkPreviewUpdater

**File: `services/notifications/NotificationLinkPreviewUpdater.kt`**

Changes:
1. Use `NotificationParamsBuilder` instead of building params inline
2. Pass link preview data as overrides

```kotlin
// Before (lines 181-201) - builds all params, missing hasContactInfo

// After
val params = notificationParamsBuilder.buildParams(
    messageGuid = message.guid,
    chatGuid = completion.chatGuid,
    messageText = message.text ?: "",
    subject = message.subject,
    senderAddress = message.senderAddress,
    linkPreviewTitle = preview.title,
    linkPreviewDomain = preview.domain ?: preview.siteName,
    attachmentUri = attachmentUri,
    attachmentMimeType = attachmentMimeType,
    fetchLinkPreview = false  // Already have preview
) ?: return

notificationService.showMessageNotification(params)
```

---

### Phase 6: Optimize Shortcut Creation

**File: `services/notifications/BubbleMetadataHelper.kt`**

Since bubbles are disabled, consider:

**Option A: Skip shortcut creation entirely**
```kotlin
fun createConversationShortcut(...): String? {
    // Bubbles disabled, skip shortcut creation
    return null
}
```

**Option B: Create shortcuts only when needed (lazy)**
```kotlin
// Only create shortcut on first notification, cache the fact it exists
private val createdShortcuts = mutableSetOf<String>()

fun ensureShortcutExists(chatGuid: String, ...): String {
    val shortcutId = "chat_$chatGuid"
    if (shortcutId in createdShortcuts) return shortcutId

    // Create shortcut...
    createdShortcuts.add(shortcutId)
    return shortcutId
}
```

**Recommendation**: Option B - keeps shortcuts for share targets while avoiding redundant work.

---

### Phase 7: Add Notification History (Optional Enhancement)

Add message history to MessagingStyle for richer notifications:

**File: `services/notifications/NotificationBuilder.kt`**

```kotlin
// Add recent message history to MessagingStyle
val recentMessages = messageDao.getRecentMessagesForChat(chatGuid, limit = 3)
    .reversed()  // Oldest first

for (msg in recentMessages) {
    val sender = if (msg.isFromMe) deviceUser else buildSenderPerson(msg)
    messagingStyle.addMessage(msg.text, msg.dateCreated, sender)
}
```

---

## File Changes Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `notifications/NotificationParamsBuilder.kt` | **New** | Central params building with caching |
| `socket/handlers/MessageEventHandler.kt` | Refactor | Use builder, remove duplicate logic |
| `fcm/FcmMessageHandler.kt` | Refactor | Use builder, add link preview parity |
| `notifications/NotificationMediaUpdater.kt` | Refactor | Use builder |
| `notifications/NotificationLinkPreviewUpdater.kt` | Refactor | Use builder |
| `notifications/BubbleMetadataHelper.kt` | Optimize | Lazy shortcut creation |
| `di/ServiceModule.kt` | Add | Provide NotificationParamsBuilder |

---

## Testing Strategy

1. **Unit tests for NotificationParamsBuilder**:
   - Contact caching works correctly
   - hasContactInfo populated for all scenarios
   - Link preview fetching optional

2. **Integration tests**:
   - Socket path produces same params as before (but with hasContactInfo)
   - FCM path produces same params as Socket path
   - Media/Link updaters produce consistent params

3. **Manual testing**:
   - Receive message via socket → verify notification
   - Kill app, receive via FCM → verify identical notification
   - Image message → verify media preview updates correctly
   - URL message → verify link preview updates correctly
   - Verify no incorrect business icons for saved contacts

---

## Migration Path

1. Create `NotificationParamsBuilder` with tests
2. Refactor one path at a time (Socket first, then FCM)
3. Verify behavior unchanged via tests
4. Refactor MediaUpdater and LinkPreviewUpdater
5. Optimize shortcut creation
6. Optional: Add message history enhancement

---

## Success Criteria

- [ ] All four notification paths use `NotificationParamsBuilder`
- [ ] Contact caching consistent across all paths
- [ ] `senderHasContactInfo` always correctly populated
- [ ] `participantHasContactInfo` always correctly populated
- [ ] FCM notifications include link preview data
- [ ] No unnecessary shortcut creation when bubbles disabled
- [ ] All existing tests pass
- [ ] No regression in notification appearance/behavior
