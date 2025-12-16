# AssistedInject Template (Phase 3)

## Layman’s explanation

We want delegates to be “born ready.” Assisted injection is the pattern that lets DI provide the normal dependencies (repositories/services), while the caller provides runtime values (like `chatGuid`).

## Typical before

- Delegate is injected once.
- Delegate has `lateinit chatGuid`.
- Caller must remember to call `initialize(chatGuid, scope)`.

## Typical after

- Delegate has no `initialize()`.
- Delegate is created by a factory, and the factory takes `chatGuid`.

## Skeleton (Kotlin + Hilt AssistedInject)

```kotlin
class ChatSendDelegate @AssistedInject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val messageSender: MessageSender,
    private val socketService: SocketService,
    private val soundManager: SoundManager,
    @Assisted private val chatGuid: String,
    @Assisted private val scope: CoroutineScope,
) {
    // No lateinit, no initialize()

    @AssistedFactory
    interface Factory {
        fun create(
            chatGuid: String,
            scope: CoroutineScope,
        ): ChatSendDelegate
    }
}
```

## How call sites change

`ChatViewModel` (or a coordinator) injects the `Factory` and calls `create(chatGuid, viewModelScope)`.

## Notes

- Prefer injecting `CoroutineScope` only if you truly need long-lived jobs inside the delegate.
- If the delegate can be written as pure suspend functions, consider passing `scope` per call instead.
- Keep assisted params minimal (usually `chatGuid` + maybe `mergedChatGuids`).
