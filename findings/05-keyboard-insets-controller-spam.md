# InsetsController Keyboard Hide Spam

## Severity: LOW (Potential UX/performance issue)

## Status: FIXED

## Occurrence Count
- 8 rapid `hide(ime())` calls within ~1.4 seconds

## Log Pattern
```
12-17 20:29:21.193 D/InsetsController(21796): hide(ime())
12-17 20:29:22.113 D/InsetsController(21796): hide(ime())
12-17 20:29:22.155 D/InsetsController(21796): hide(ime())
12-17 20:29:22.211 D/InsetsController(21796): hide(ime())
12-17 20:29:22.278 D/InsetsController(21796): hide(ime())
12-17 20:29:22.375 D/InsetsController(21796): hide(ime())
12-17 20:29:22.448 D/InsetsController(21796): hide(ime())
12-17 20:29:22.574 D/InsetsController(21796): hide(ime())
```

## Impact
- Unnecessary system calls
- May cause keyboard animation flickering
- Could impact UI performance during navigation

## Root Cause (Identified)

**Primary culprit**: `ChatCreatorScreen.kt` lines 250-255

```kotlin
// PROBLEMATIC PATTERN:
LaunchedEffect(listState.isScrollInProgress) {
    if (listState.isScrollInProgress) {
        keyboardController?.hide()
    }
}
```

**Why this causes spam**: `listState.isScrollInProgress` is a rapidly-changing state that toggles multiple times during a scroll gesture:
- Toggles `true` when scroll starts
- Can briefly toggle `false`/`true` when direction changes
- Toggles during fling deceleration phases
- Each `true` transition fires a new `hide()` call

## Fix Applied

Changed to debounced pattern using `snapshotFlow` with state tracking:

```kotlin
// FIXED PATTERN:
LaunchedEffect(listState) {
    var keyboardHidden = false
    snapshotFlow { listState.isScrollInProgress }
        .collect { isScrolling ->
            if (isScrolling && !keyboardHidden) {
                keyboardController?.hide()
                keyboardHidden = true
            } else if (!isScrolling) {
                keyboardHidden = false
            }
        }
}
```

This ensures `hide()` is called only once per scroll gesture.

## Other Keyboard Hide Locations (Reviewed)

| File | Pattern | Status |
|------|---------|--------|
| `ChatScrollHelper.kt:107` | Debounced (750px threshold) | OK |
| `ChatComposer.kt:111` | Panel change trigger | OK |
| `GifPickerPanel.kt:307` | Debounced (scroll threshold) | OK |

## Files Changed
- `app/src/main/kotlin/com/bothbubbles/ui/chatcreator/ChatCreatorScreen.kt`
