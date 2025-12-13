# ChatViewModel Delegates

This package contains delegates that decompose `ChatViewModel` into focused, single-responsibility classes. Each delegate handles a specific concern and can be injected and tested independently.

## Pattern Overview

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatSendDelegate: ChatSendDelegate,
    private val chatAttachmentDelegate: ChatAttachmentDelegate,
    // ... other delegates
) : ViewModel() {
    init {
        chatSendDelegate.initialize(chatGuid, viewModelScope)
        chatAttachmentDelegate.initialize(chatGuid, viewModelScope)
    }

    // Delegate public methods
    fun sendMessage() = chatSendDelegate.sendMessage(...)
    fun downloadAttachment(guid: String) = chatAttachmentDelegate.downloadAttachment(guid)
}
```

## Implemented Delegates

### `ChatSendDelegate`
Handles all message sending operations:
- Send text/attachments via iMessage or SMS/MMS
- Message queue management (offline-first)
- Retry failed messages
- Forward messages to other chats
- Reply state management
- Typing indicator coordination

### `ChatAttachmentDelegate`
Handles attachment download operations:
- Download progress tracking
- Queue downloads with priority (IMMEDIATE, ACTIVE_CHAT)
- Auto-download management
- Download completion notifications
- Active chat prioritization

## Planned Delegates (TODO)

### `ChatInputDelegate`
Should handle:
- Draft text management with debounced persistence
- Smart reply generation (ML Kit + templates)
- Character/segment counting for SMS
- Input focus state

### `ChatMetadataDelegate`
Should handle:
- Chat/participant info loading
- Contact lookup and caching
- Fallback mode tracking (iMessage <-> SMS)
- iMessage availability checking
- Save contact banner logic

### `ChatPagingDelegate`
Should handle:
- Message pagination (BitSet-based)
- Server sync for gaps
- Scroll position tracking
- Total message count

### `ChatEffectsDelegate`
Should handle:
- Screen effect queue and playback
- Effect settings observation
- Message effect triggers

## Migration Guide

When migrating functionality from `ChatViewModel`:

1. Create a new `*Delegate` class in this package
2. Add `@Inject constructor` with required dependencies
3. Add `initialize(chatGuid, scope)` method
4. Move related state flows and methods
5. Update `ChatViewModel` to inject and delegate
6. Add tests for the new delegate

Benefits:
- Smaller, testable units
- Clear separation of concerns
- Easier to understand and maintain
- Reusable across ViewModels (e.g., BubbleChatViewModel)
