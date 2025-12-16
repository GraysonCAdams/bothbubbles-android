# Wave 3B (Parallel): Remove Duplicate Sets from ChatMessageList

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatMessageList.kt`

---

## Objective

Remove the TODO-marked duplicate sets and update all references to use `chatScreenState.*`.

---

## Tasks

### 1. Remove Duplicate Set Declarations

Find and remove these lines (around lines 305-317):

```kotlin
// REMOVE these lines:
// TODO(Wave2): Remove - now using chatScreenState.processedEffectMessages
val processedEffectMessages = remember { mutableSetOf<String>() }

// TODO(Wave2): Remove - now using chatScreenState.animatedMessageGuids
val animatedMessageGuids = remember { mutableSetOf<String>() }

// TODO(Wave2): Remove - now using chatScreenState.revealedInvisibleInkMessages
var revealedInvisibleInkMessages by remember { mutableStateOf(setOf<String>()) }
```

### 2. Update processedEffectMessages References

Find all usages and replace:

```kotlin
// BEFORE
processedEffectMessages.add(guid)
guid in processedEffectMessages

// AFTER
chatScreenState.markEffectProcessed(guid)
chatScreenState.isEffectProcessed(guid)
```

### 3. Update animatedMessageGuids References

Find all usages and replace:

```kotlin
// BEFORE
animatedMessageGuids.add(guid)
guid in animatedMessageGuids
animatedMessageGuids.addAll(guids)

// AFTER
chatScreenState.markMessageAnimated(guid)
chatScreenState.isMessageAnimated(guid)
chatScreenState.markInitialMessagesAnimated(guids)
```

### 4. Update revealedInvisibleInkMessages References

Find all usages and replace:

```kotlin
// BEFORE
revealedInvisibleInkMessages += guid
revealedInvisibleInkMessages -= guid
guid in revealedInvisibleInkMessages

// AFTER
chatScreenState.revealInvisibleInk(guid)
chatScreenState.concealInvisibleInk(guid)
chatScreenState.isInvisibleInkRevealed(guid)
```

### 5. Remove Duplicate Screen Effect LaunchedEffect

If there's a duplicate screen effect detection LaunchedEffect in ChatMessageList (similar to the one in ChatScreen), remove it:

```kotlin
// REMOVE if duplicating ChatScreen logic:
LaunchedEffect(messages.firstOrNull()?.guid) {
    val newest = messages.firstOrNull() ?: return@LaunchedEffect
    if (newest.guid in processedEffectMessages) return@LaunchedEffect
    // screen effect logic...
}
```

Screen effect detection should only happen in `ChatScreen.kt`.

---

## Verification

- [ ] No local `remember { mutableSetOf() }` for tracking sets
- [ ] No local `remember { mutableStateOf(setOf()) }` for revealed ink
- [ ] All references use `chatScreenState.*` helper methods
- [ ] No duplicate screen effect LaunchedEffect
- [ ] Build succeeds: `./gradlew assembleDebug`

---

## Notes

- The `chatScreenState` parameter should already be passed from Wave 1
- If helper methods are missing from `ChatScreenState`, check that Wave 1 ChatScreenState task completed
