# Phase 3 — Delegate Lifecycle (Remove `initialize()` Footguns)

> **Implementation Plan**: See [impl/README.md](impl/README.md) for detailed migration steps, code examples, and checklists.
>
> **Recommendation**: Combine with Phase 2 for efficiency — change lifecycle and interfaces together.

## Layman's Explanation

Today, some delegates are created "empty" and only become safe after `initialize(chatGuid, scope)` is called. That's like buying a car where the brakes only work if you remember to pull a hidden lever first.

This phase makes delegates *always safe to use* by construction, so future changes can't accidentally forget initialization.

## Connection to Shared Vision

This phase implements [ADR 0004](../phase_0_shared_vision/ADR_0004_delegate_lifecycle_rules.md):

> **Delegates are "born ready" — safe to use immediately after construction.**

## Key Transformation

```kotlin
// BEFORE - Temporal coupling
class ChatSendDelegate @Inject constructor(...) {
    private lateinit var chatGuid: String       // Can crash!
    fun initialize(chatGuid: String, scope: CoroutineScope) {
        this.chatGuid = chatGuid  // Must remember!
    }
}

// AFTER - Safe by construction
class ChatSendDelegate @AssistedInject constructor(
    @Assisted private val chatGuid: String,  // Required at construction
    @Assisted private val scope: CoroutineScope
) {
    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

## Goals

- Eliminate `lateinit` state required for correctness
- Make delegate APIs enforce correctness at compile time
- Keep the performance benefits of state isolation

## Pattern: AssistedInject (Default)

```kotlin
class ChatSendDelegate @AssistedInject constructor(
    private val messageSender: MessageSender,         // DI injected
    @Assisted private val chatGuid: String,           // Runtime param
    @Assisted private val scope: CoroutineScope       // Runtime param
) {
    // No lateinit, no initialize()

    @AssistedFactory
    interface Factory {
        fun create(chatGuid: String, scope: CoroutineScope): ChatSendDelegate
    }
}
```

## Migration Order

| Order | Delegate | Complexity |
|-------|----------|------------|
| 1 | ChatConnectionDelegate | Low |
| 2-6 | Info, Effects, Attachment, Scheduled, Thread | Low-Medium |
| 7-11 | Search, Sync, Composer, MessageList, Operations | Medium-High |
| 12-14 | SendModeManager, EtaSharing, **ChatSendDelegate** | High |

## Important: Keep setDelegates() Temporarily

Phase 3 changes **lifecycle only**. Keep cross-delegate wiring (`setDelegates()`) for now:

```kotlin
// KEEP THIS - Phase 4 removes it
send.setDelegates(messageList, composer, ...)
```

## Exit Criteria

- [ ] All Chat delegates use `@AssistedInject`
- [ ] No `lateinit var` for chatGuid/scope in delegates
- [ ] No `initialize()` methods in delegates
- [ ] ChatViewModel uses Factory injection
- [ ] `setDelegates()` still exists (Phase 4 removes it)
- [ ] Build passes and app functions correctly

## Risks

- High: touches many call sites
- Mitigated by: mechanical changes, one delegate at a time, combined with Phase 2

## Next Steps

After Phase 3, proceed to Phase 4 (Delegate Coupling Reduction) to remove `setDelegates()`.
