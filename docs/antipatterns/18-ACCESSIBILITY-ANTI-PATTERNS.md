# Accessibility Anti-Patterns

**Scope:** Screen readers, touch targets, semantics, contrast

---

## High Severity Issues

### 1. Missing contentDescription on Icons (55+ files)

**Locations:**
- `SetupServerPage.kt:127` - QR scanner icon
- `ChatIndicators.kt:218-220` - KeyboardArrowDown icon
- `NotificationProviderScreen.kt:195` - Refresh icon
- `AboutScreen.kt` - Multiple navigation icons

**Issue:**
```kotlin
Icon(Icons.Default.Refresh, contentDescription = null)
```

**Fix:**
```kotlin
Icon(Icons.Default.Refresh, contentDescription = "Refresh notifications")
```

---

### 2. Clickable Elements Without Semantics

**Locations:**
- `MessageBubble.kt:178` - Row clickable for selection
- `MessageBubble.kt:195` - Avatar clickable
- `ConversationTile.kt` - Combined clickable patterns

**Issue:**
```kotlin
Modifier.clickable(onClick = handleSelectionToggle)
// Missing role declaration
```

**Fix:**
```kotlin
Modifier
    .clickable(onClick = handleSelectionToggle)
    .semantics {
        onClick(label = "Toggle selection") {}
    }
```

---

### 3. Hard-Coded Colors (Contrast Issues)

**Locations:**
- `MessageSegmentedBubble.kt:705` - `Color(0xFFFF9800)` search highlight
- `MessageSimpleBubble.kt:467` - Same orange highlight
- `MessageDeliveryIndicators.kt:152` - `Color(0xFF34B7F1)` delivery indicator

**Fix:** Use `MaterialTheme.colorScheme.*` for theme-appropriate contrast.

---

## Medium Severity Issues

### 4. Touch Targets Below 48dp

**Locations:**
- `MessageSegmentedBubble.kt:614` - Refresh icon 14.dp
- `ChatIndicators.kt:225` - Arrow icon 18.dp
- `MessageDeliveryIndicators.kt:95-117` - Delivery indicators 14dp
- `ReactionPill.kt:165` - Emoji reaction 40.dp

**Fix:**
```kotlin
IconButton(
    onClick = { ... },
    modifier = Modifier.size(48.dp)  // Ensures 48dp minimum
) {
    Icon(Icons.Default.Refresh, modifier = Modifier.size(24.dp))
}
```

---

### 5. Missing Heading Semantics

**Locations:**
- `SetupServerPage.kt:78-83` - "BlueBubbles Server" heading
- `NotificationSettingsScreen.kt` - Multiple settings headings

**Fix:**
```kotlin
Text(
    text = "BlueBubbles Server",
    style = MaterialTheme.typography.headlineMedium,
    modifier = Modifier.semantics { heading() }
)
```

---

### 6. Menu Icons Without Context

**Locations:**
- `MessageSegmentedBubble.kt:856-881` - Phone menu icons
- `MessageSimpleBubble.kt:726-762` - Similar menu patterns

**Fix:** Add `contentDescription` even when icon is paired with text label.

---

## Low Severity Issues

### 7. Missing Labels on Form Inputs

**Locations:**
- `SetupServerPage.kt:151-162` - Server URL field
- `SetupServerPage.kt:167-190` - Password field

**Note:** Already partially addressed with `label` parameter.

---

### 8. Missing Role Modifiers on Custom Components

**Locations:**
- `ReactionPill.kt:56-177` - Custom reaction selector
- `TapbackCard.kt:69-192` - Custom action card

**Fix:**
```kotlin
.semantics {
    role = Role.Button
    onClick(label = "Select reaction") {}
}
```

---

### 9. Animation Without Motion Sensitivity

**Locations:**
- `ChatIndicators.kt:72-101` - TypingDot infinite animation
- `ConversationTile.kt:79-102` - Color and shape animations

**Fix:** Respect `reduceMotion` preference from system settings.

---

## Summary Table

| Issue | Severity | Count | Impact |
|-------|----------|-------|--------|
| Missing contentDescription | HIGH | 55+ files | Screen reader users can't understand icons |
| Clickable without semantics | HIGH | 3+ locations | Missing role information |
| Hard-coded colors | HIGH | 3 locations | Contrast issues in dark mode |
| Touch targets < 48dp | MEDIUM | 4+ locations | Difficult to activate |
| Missing heading semantics | MEDIUM | Multiple | Navigation challenges |
| Menu icons without context | MEDIUM | 2 locations | Missing semantic context |
| Missing form labels | LOW | 2 locations | Minor clarity issues |
| Missing role modifiers | LOW | 2 components | Custom component semantics |
| Animation sensitivity | LOW | 2 locations | Motion sensitivity |
