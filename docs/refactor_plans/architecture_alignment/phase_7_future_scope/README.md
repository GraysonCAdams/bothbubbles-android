# Phase 7 — Future Scope & Follow-on Work

## Layman’s explanation

The Chat screen was the biggest and most complex part of the app, so we fixed it first. But other parts of the app (like the Conversation List and Setup screens) were built with the same "old" patterns.

This phase is a reminder list of what to fix *next*, so we don't leave the app in a half-modernized state forever.

## Primary Target: ConversationsViewModel

The `ConversationsViewModel` (880+ lines) manages the list of chats. It suffers from the same issues we just fixed in Chat:

- **Delegate Lifecycle:** Uses `initialize()` + `lateinit` pattern.
- **Callback Hell:** `ConversationObserverDelegate` takes 4+ callbacks in its initialize method, creating a tangle of logic.
- **Manual Wiring:** Delegates are manually wired in the ViewModel.

**Action:** Apply the same "AssistedInject + Interface" pattern used in Phase 3 to `ConversationsViewModel`.

## Secondary Targets

### 1. SetupViewModel DI
- **Issue:** `SetupViewModel` currently constructs delegates manually (`val x = Delegate(context)`).
- **Fix:** Convert to Hilt injection.

### 2. Service Layer Initialization
- **Issue:** `BothBubblesApp.onCreate()` calls `.initialize()` on many singleton managers.
- **Fix:** Move to standard DI initialization or `Startup` library where appropriate to ensure safety.

## When to start this?

- **Do not** start this until Chat (Phases 1-5) is stable and shipped.
- Treat this as "Technical Debt Paydown" for the next release cycle.
