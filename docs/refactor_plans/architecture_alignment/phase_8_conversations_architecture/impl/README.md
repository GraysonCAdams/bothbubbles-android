# Phase 8: Conversations Architecture — Implementation Plan

> **Status**: Planned
> **Goal**: Modernize `ConversationsViewModel` using AssistedInject and Event-based communication.

## Overview

`ConversationsViewModel` is currently a "God Class" with manual delegate initialization and callback hell. We will refactor it to match the clean architecture of `ChatViewModel`.

## Core Principles

1.  **AssistedInject**: Delegates are created via factories, not `lateinit var`.
2.  **Events over Callbacks**: Delegates emit `SharedFlow<Event>` instead of calling interface methods.
3.  **Explicit Coordination**: ViewModel observes events and decides what to do.

## Step-by-Step Implementation

### Step 1: Define `ConversationEvent`

Create a sealed class to represent all things that can happen in the list:

```kotlin
sealed class ConversationEvent {
    data object RefreshRequested : ConversationEvent()
    data class ChatSelected(val chat: Chat) : ConversationEvent()
    data class ChatDeleted(val guids: List<String>) : ConversationEvent()
    data class ChatArchived(val guids: List<String>) : ConversationEvent()
    // ...
}
```

### Step 2: Refactor `ConversationObserverDelegate`

**Current:**
```kotlin
class ConversationObserverDelegate {
    fun initialize(scope: CoroutineScope, callbacks: ConversationCallbacks) { ... }
}
```

**Target:**
```kotlin
class ConversationObserverDelegate @AssistedInject constructor(
    private val chatRepository: ChatRepository,
    @Assisted private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<ConversationEvent>()
    val events = _events.asSharedFlow()
    
    // ... logic emits events ...
}
```

### Step 3: Refactor `ConversationFilterDelegate`

Convert to `AssistedInject`. This delegate likely manages search/filter state. Expose a `StateFlow<FilterState>`.

### Step 4: Update `ConversationsViewModel`

1.  Inject factories: `ConversationObserverDelegate.Factory`, etc.
2.  Initialize delegates in `init {}` block (or property declaration).
3.  Collect events:
    ```kotlin
    init {
        viewModelScope.launch {
            observer.events.collect { event ->
                when(event) {
                    is ConversationEvent.ChatSelected -> navigateToChat(event.chat)
                    // ...
                }
            }
        }
    }
    ```

## Work Breakdown Checklist

| Item | Description | Owner | Status |
|------|-------------|-------|--------|
| CLD-01 | `ConversationLoadingDelegate` converted to AssistedInject factory | _Unassigned_ | ☐ |
| CLD-02 | `ConversationActionsDelegate` rebuilt around explicit APIs (no callbacks) | _Unassigned_ | ☐ |
| CLD-03 | `ConversationObserverDelegate` emits `ConversationEvent` via `SharedFlow` | _Unassigned_ | ☐ |
| CLD-04 | `ConversationFilterDelegate` exposes `StateFlow<FilterState>` | _Unassigned_ | ☐ |
| CLD-05 | `UnifiedGroupMappingDelegate` lifecycle-safe factory + disposal hook | _Unassigned_ | ☐ |
| CLD-06 | `ConversationsViewModel` orchestrates delegates and owns state machine | _Unassigned_ | ☐ |

## Verification

*   **Regression Test**: Verify conversation list loads, updates on new messages, and search works.
*   **Code Audit**: Ensure no `lateinit var` delegates remain in `ConversationsViewModel`.
