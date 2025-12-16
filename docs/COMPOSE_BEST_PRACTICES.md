# Jetpack Compose Best Practices & Performance Guidelines

This document outlines the mandatory architectural patterns and coding standards for Jetpack Compose development in BothBubbles. These rules are designed to prevent performance regressions, "God Composables," and stability issues.

## 1. State Management

### Leaf-Node State Collection
**Rule**: A Composable should only collect state if it *directly* renders it. Do not collect state in a parent just to pass it down to a child.

**Bad**:
```kotlin
// ChatScreen collects state it doesn't use
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val downloadingAttachments by viewModel.downloadingAttachments.collectAsStateWithLifecycle()
    ChatMessageList(downloadingAttachments = downloadingAttachments)
}
```

**Good**:
```kotlin
// ChatScreen passes a delegate/interface
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    ChatMessageList(attachmentDelegate = viewModel.attachmentDelegate)
}

// Child collects what it needs
@Composable
fun ChatMessageList(attachmentDelegate: AttachmentDelegate) {
    val downloadingAttachments by attachmentDelegate.downloadingAttachments.collectAsStateWithLifecycle()
}
```

### Immutable Collections
**Rule**: Use `kotlinx.collections.immutable` (`ImmutableList`, `ImmutableMap`) for all UI state. Standard `List` and `Map` are considered unstable by the Compose compiler and will cause unnecessary recompositions.

**Bad**:
```kotlin
data class ChatUiState(
    val messages: List<Message> // Unstable
)
```

**Good**:
```kotlin
data class ChatUiState(
    val messages: ImmutableList<Message> // Stable
)
```

## 2. Stability & Recomposition

### Stable Callbacks
**Rule**: Event callbacks exposed by UI components must be stable. Avoid inline lambdas that capture unstable state. Use method references or stable interfaces.

**Bad**:
```kotlin
// Recreated on every recomposition if 'messages' changes
ChatScreen(
    onSearch = { query -> viewModel.search(query, messages) }
)
```

**Good**:
```kotlin
// Stable method reference
ChatScreen(
    onSearch = viewModel::search
)
```

### Side-Effect Isolation
**Rule**: No business logic in Composition. Use `DisposableEffect` or `LaunchedEffect` strictly for UI-lifecycle binding.

*   **Do not** call `System.currentTimeMillis()` or `Log.d()` directly in the composition body.
*   **Do not** perform synchronous I/O (e.g., `MediaActionSound.load()`) in composition.

## 3. Tooling & Enforcement

### Compose Compiler Metrics
We have enabled Compose Compiler Metrics. You can generate a report to check for unstable classes:
```bash
./gradlew assembleRelease -Papp.enableComposeCompilerReports=true
```
Check `app/build/compose_compiler/` for `compose_metrics.json`.

### Linting
We use Slack's Compose Lint Checks. The build will fail if you violate common rules (e.g., passing `MutableState` as a parameter).

## 4. Common Pitfalls to Avoid

*   **God Composables**: If a Composable has more than 5-7 state parameters, it is likely doing too much. Refactor by pushing state down or grouping into a stable State class.
*   **Stale Captures**: Be careful with `LaunchedEffect(Unit)`. If it collects a flow that depends on a captured variable (like a list), it might use a stale version of that list. Use `rememberUpdatedState` or restart the effect when the dependency changes.
