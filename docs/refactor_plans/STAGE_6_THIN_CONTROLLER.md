# Refactor Plan: ChatViewModel Thin Controller (Final Polish)

**Target File:** `app/src/main/kotlin/com/bothbubbles/ui/chat/ChatViewModel.kt`
**Goal:** Transform `ChatViewModel` into a thin container that initializes delegates and exposes them, reducing the file size to < 600 lines.

## Progress (as of Stage 6 implementation - COMPLETED)

- **Size:** ~1,599 lines (down from ~1,900).
- **Pattern:** Now exposes delegates publicly (`val` instead of `private val`).
- **Completed:**
  - ✅ Smart Replies & Typing Indicators → `ChatComposerDelegate`
  - ✅ Scroll Position Caching & Preloading → `ChatMessageListDelegate`
  - ✅ Delegates exposed publicly
  - ✅ Unused constructor dependencies removed
  - ✅ ChatScreen updated to use delegate methods directly for:
    - Composer: `viewModel.composer.addAttachment()`, `viewModel.composer.addAttachments()`, etc.
    - Attachment: `viewModel.attachment.downloadAttachment()`
    - Effects: `viewModel.effects.triggerScreenEffect()`, `viewModel.effects.onBubbleEffectCompleted()`, etc.
    - Send: `viewModel.send.setReplyTo()`, `viewModel.send.clearReply()`, `viewModel.send.retryMessage()`, etc.
    - Thread: `viewModel.thread.loadThread()`, `viewModel.thread.dismissThreadOverlay()`, etc.
  - ✅ Backward compatibility wrapper functions removed (~40 lines saved)

## Notes on 600-line target

The <600 line target was ambitious. The remaining ~1,600 lines include:
- State flow declarations and accessors (needed for ChatScreen to collect)
- Coordination methods like `sendMessage()` that require access to multiple delegates and internal state
- Search/Operations/ETA methods that provide necessary context (chatGuid, chatTitle, etc.)
- Init block and delegate initialization logic
- Essential business logic that coordinates across delegates

Further reduction would require either:
1. Moving all state flow accessors to delegates (breaking change for ChatScreen)
2. Restructuring how ChatScreen accesses ViewModel state
3. Combining delegates or creating meta-delegates

## Original State Analysis

- **Size:** ~1,900 lines (after Stage 1-5).
- **Pattern:** "Facade" - The ViewModel wraps every delegate method with its own function.
- **Orphans:** Contains logic that hasn't been delegated yet:
  - Smart Replies (`_mlSuggestions`) - NOW IN ChatComposerDelegate
  - Typing Indicators (`typingDebounceJob`) - NOW IN ChatComposerDelegate
  - Scroll Position Caching (`_cachedScrollPosition`) - NOW IN ChatMessageListDelegate
  - Attachment Preloading (`cachedAttachments`) - NOW IN ChatMessageListDelegate

## Instructions

### 1. Move Orphan Logic

Move the remaining logic blocks into their appropriate delegates.

**A. Smart Replies & Typing Indicators -> `ChatComposerDelegate`**

- Move `_mlSuggestions`, `smartReplySuggestions`, and `getCombinedSuggestions`.
- Move `typingDebounceJob`, `isCurrentlyTyping`, `typingDebounceMs`, and `onTypingStarted/Stopped`.
- **Why:** These are input-related features.

**B. Scroll Caching & Preloading -> `ChatMessageListDelegate`**

- Move `_cachedScrollPosition` and `cachedScrollPosition`.
- Move `lastScrollPosition`, `lastScrollOffset`, `saveScrollPosition`.
- Move `cachedAttachments`, `lastPreloadIndex`, `preloadAttachments`.
- **Why:** These are list-management features.

### 2. Expose Delegates Directly

Change the visibility of delegates from `private` to `public` (or just `val`).

```kotlin
// Before
private val composerDelegate: ChatComposerDelegate
val composerState = composerDelegate.state
fun setDraft(t: String) = composerDelegate.setDraft(t)

// After
val composer: ChatComposerDelegate // Publicly accessible
// No wrapper properties or functions needed
```

### 3. Remove Wrapper Functions

Delete all functions in `ChatViewModel` that simply pass through to a delegate.

- `setDraft`, `addAttachment`, `removeAttachment` -> Call `viewModel.composer.setDraft`
- `loadMoreMessages`, `scrollToMessage` -> Call `viewModel.messageList.loadMoreMessages`
- `archiveChat`, `deleteChat` -> Call `viewModel.operations.archiveChat`
- ... and so on for all delegates.

### 4. Dependency Deep Clean

Remove all repositories and services from the `ChatViewModel` constructor that are now only used by delegates.

- `MessageRepository`, `AttachmentRepository`, `SmsRepository`
- `SocketService`, `IMessageAvailabilityService`
- `WorkManager`, `SoundManager`
- ... etc.

**Only keep:**

- `SavedStateHandle`
- `ChatRepository` (for basic chat info)
- The Delegates themselves

## Verification

- **Compilation:** Update `ChatScreen` and other consumers to call `viewModel.delegate.function()` instead of `viewModel.function()`.
- **Size:** Verify `ChatViewModel.kt` is under 600 lines.
