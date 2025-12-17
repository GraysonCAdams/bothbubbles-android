# Haptic Feedback Guidelines

This document defines the standard haptic feedback patterns for BothBubbles. All new code MUST follow these guidelines for consistent user experience.

## Quick Reference

| User Action | HapticUtils Function | Haptic Type | Example |
|-------------|---------------------|-------------|---------|
| Button/menu tap | `onTap()` | TextHandleMove | Send button, settings items |
| Long-press start | `onLongPress()` | LongPress | Context menus, voice recording |
| Selection confirmed | `onConfirm()` | LongPress | Emoji selected, swipe completed |
| Threshold crossed | `onThresholdCrossed()` | LongPress | Pull-to-refresh, mode toggle |
| Drag transition | `onDragTransition()` | TextHandleMove | Emoji scrubbing, reordering |
| Disabled element tap | `onDisabledTap()` | LongPress | Tapping disabled menu item |

## Usage

Always use the centralized `HapticUtils` object instead of calling `performHapticFeedback` directly:

```kotlin
// CORRECT - Use HapticUtils
val haptic = LocalHapticFeedback.current
Button(onClick = {
    HapticUtils.onTap(haptic)
    doAction()
})

// INCORRECT - Direct call
Button(onClick = {
    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)  // Don't do this
    doAction()
})
```

## Detailed Guidelines

### 1. Button and Menu Taps

Use `HapticUtils.onTap()` for all interactive tappable elements:

- Buttons (IconButton, TextButton, Surface onClick)
- Menu items in settings
- Links and actionable text
- Chips and badges

```kotlin
IconButton(onClick = {
    HapticUtils.onTap(haptic)
    onCameraClick()
}) {
    Icon(Icons.Outlined.CameraAlt, contentDescription = "Camera")
}
```

### 2. Long-Press Actions

Use `HapticUtils.onLongPress()` when a long-press gesture is recognized:

- Context menu appearing
- Voice recording starting
- Drag-to-reorder initiating
- Effect picker opening

```kotlin
Modifier.combinedClickable(
    onClick = { /* normal tap */ },
    onLongClick = {
        HapticUtils.onLongPress(haptic)
        showContextMenu()
    }
)
```

### 3. Selection Confirmation

Use `HapticUtils.onConfirm()` when a selection or action is finalized:

- Emoji/reaction selected from picker
- Swipe action completed
- Toggle switch changed
- Mode selection confirmed

```kotlin
// When user releases finger on selected emoji
onDragEnd = {
    if (selectedEmoji != null) {
        HapticUtils.onConfirm(haptic)
        onEmojiSelected(selectedEmoji)
    }
}

// Toggle switch
Switch(
    checked = checked,
    onCheckedChange = {
        HapticUtils.onConfirm(haptic)
        onCheckedChange(it)
    }
)
```

### 4. Threshold Crossing

Use `HapticUtils.onThresholdCrossed()` when dragging past a meaningful boundary:

- Pull-to-refresh threshold reached
- Swipe action threshold crossed
- Send mode toggle threshold

```kotlin
// In drag gesture handler
if (dragOffset > threshold && !hasTriggeredThreshold) {
    hasTriggeredThreshold = true
    HapticUtils.onThresholdCrossed(haptic)
}
```

**Important**: Only trigger ONCE per threshold crossing. Track state to avoid repeated haptics.

### 5. Drag Transitions

Use `HapticUtils.onDragTransition()` for feedback during continuous drag:

- Emoji scrubbing (moving between options)
- Attachment reordering (hovering over positions)
- Reaction pill dragging

**Important**: Use `ThrottledHaptic` for high-frequency drags to avoid overwhelming feedback:

```kotlin
val throttledHaptic = rememberThrottledHaptic(haptic)

Modifier.pointerInput(Unit) {
    detectDragGestures { change, _ ->
        val newHoveredItem = calculateHoveredItem(change.position)
        if (newHoveredItem != currentHoveredItem) {
            currentHoveredItem = newHoveredItem
            throttledHaptic.onDragTransition()
        }
    }
}
```

### 6. Disabled State

Use `HapticUtils.onDisabledTap()` combined with visual feedback (shake animation):

```kotlin
onClick = {
    if (enabled) {
        HapticUtils.onTap(haptic)
        doAction()
    } else {
        HapticUtils.onDisabledTap(haptic)
        triggerShakeAnimation()
        showExplanationSnackbar()
    }
}
```

## Anti-Patterns

### DON'T: Double haptics

Avoid triggering haptics in both parent and child for the same action:

```kotlin
// BAD - Parent and child both trigger haptic
ParentComposable(
    onClick = {
        haptic.performHapticFeedback(...)  // First haptic
        ChildComposable()  // Child also has haptic
    }
)
```

### DON'T: Haptics on every frame

Avoid triggering haptics on continuous drag without throttling:

```kotlin
// BAD - Fires every frame during drag
Modifier.pointerInput(Unit) {
    detectDragGestures { _, _ ->
        haptic.performHapticFeedback(...)  // Too frequent!
    }
}

// GOOD - Throttled
val throttled = rememberThrottledHaptic(haptic)
Modifier.pointerInput(Unit) {
    detectDragGestures { _, _ ->
        if (itemChanged) throttled.onDragTransition()
    }
}
```

### DON'T: Multiple haptics for one gesture

Avoid multiple haptics for a single user action:

```kotlin
// BAD - Double haptic for pull-to-refresh
if (offset > threshold) {
    haptic.performHapticFeedback(...)  // Threshold
}
if (released) {
    haptic.performHapticFeedback(...)  // Release - unnecessary
}

// GOOD - Single haptic at threshold
if (offset > threshold && !hasTriggered) {
    hasTriggered = true
    HapticUtils.onThresholdCrossed(haptic)
}
```

### DON'T: Unused haptic collection

Remove `LocalHapticFeedback.current` if not used:

```kotlin
// BAD - Collected but never used
@Composable
fun MyScreen() {
    val haptic = LocalHapticFeedback.current  // Dead code
    // ... no performHapticFeedback calls
}
```

## Checklist for Code Review

- [ ] Uses `HapticUtils` instead of direct `performHapticFeedback` calls
- [ ] Appropriate haptic type for the interaction
- [ ] No double haptics (parent + child)
- [ ] High-frequency drags use `ThrottledHaptic`
- [ ] Threshold haptics only fire once
- [ ] No unused `LocalHapticFeedback.current` collections
