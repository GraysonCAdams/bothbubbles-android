# Navigation Anti-Patterns

**Scope:** Compose Navigation, deep links, back stack, state

---

## High Severity Issues

### 1. Unsafe Back Stack Entry Retrieval

**Locations:**
- `NavHost.kt` (lines 101-102, 117-118)
- `SetupShareNavigation.kt` (lines 44, 65)
- `ChatNavigation.kt` (line 277)

**Issue:**
```kotlin
navController.navigate(chatRoute) { ... }
// Immediately after:
val chatEntry = navController.getBackStackEntry(chatRoute)  // May not exist yet!
```

**Problem:** Back stack entry may not exist immediately after navigate().

**Fix:** Use `navController.currentBackStackEntry` or pass data via composable parameters.

---

### 2. Insufficient Deep Link Validation

**Location:** `MainActivity.kt` (lines 249-261)

**Issue:**
```kotlin
val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID) ?: return null
// No validation for empty strings, format, or existence
```

**Fix:**
```kotlin
val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID)
    ?.takeIf { it.isNotBlank() && it.isValidGuid() } ?: return null
```

---

### 3. Unchecked JSON in Navigation Arguments

**Locations:**
- `Screen.kt` (line 44)
- `ChatNavigation.kt` (lines 130-131, 140-141)

**Issue:**
```kotlin
data class GroupSetup(
    val participantsJson: String,  // Raw JSON, no validation!
    val groupService: String
) : Screen
```

**Fix:** Pass typed data class instead of JSON string.

---

## Medium Severity Issues

### 4. Bare Exception Catching

**Locations:**
- `NavHost.kt` (lines 100-108, 113-130)
- `ChatNavigation.kt` (line 277)
- `SetupShareNavigation.kt` (lines 43-53, 64-71)

**Issue:**
```kotlin
} catch (_: Exception) {
    // Entry might not be found immediately
}
```

**Problem:** Masks real errors, makes debugging difficult.

**Fix:** Catch specific exceptions, add logging.

---

### 5. Hard-Coded Route String Matching

**Location:** `NavHost.kt` (lines 196-216)

**Issue:**
```kotlin
val screen: Screen? = when (destination) {
    "server" -> Screen.ServerSettings(returnToSettings)
    "setup" -> Screen.Setup(...)
    // ... 10+ more string matches
    else -> null
}
```

**Problem:** No compile-time safety, silent failures on typos.

**Fix:** Create `SettingsDestination` sealed class.

---

### 6. Magic String Keys in SavedStateHandle

**Locations:** Multiple files with duplicated keys

**Duplicated Keys:**
- `"open_settings_panel"` - NavHost.kt, ChatNavigation.kt
- `"restore_scroll_position"` - NavHost.kt, ChatNavigation.kt
- `"shared_text"`, `"shared_uris"` - Multiple locations

**Fix:**
```kotlin
object NavigationKeys {
    const val RESTORE_SCROLL_POSITION = "restore_scroll_position"
    const val SHARED_TEXT = "shared_text"
    // ...
}
```

---

### 7. Unsafe URI.parse Without Validation

**Location:** `ChatNavigation.kt` (lines 250, 264, 274)

**Issue:**
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))  // No validation!
```

**Fix:**
```kotlin
try {
    val uri = Uri.parse(url)
    if (uri.scheme in listOf("http", "https")) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
} catch (e: Exception) {
    Timber.e(e, "Failed to parse URI: $url")
}
```

---

### 8. Activity.recreate() for New Intent

**Location:** `MainActivity.kt` (lines 151-159)

**Issue:**
```kotlin
override fun onNewIntent(intent: Intent) {
    if (shareIntentData != null) {
        recreate()  // Destroys everything!
    }
}
```

**Problem:** Loses navigation state, unsaved data, causes screen flicker.

**Fix:** Use StateFlow to pass new intent data, let Compose handle update.

---

### 9. Complex Navigation State Coordination

**Location:** `NavHost.kt` (lines 60-130)

**Issue:** Three separate `LaunchedEffect` blocks with overlapping priority logic.

**Fix:** Extract prioritization into single function returning sealed class.

---

## Low Severity Issues

### 10. Missing Navigation Result Handling

**Issue:** Several screens navigate without proper result pattern (e.g., ChatDetails → ChatScreen).

---

## Summary Table

| Issue | Severity | Count | Files |
|-------|----------|-------|-------|
| Unsafe back stack entry | HIGH | 4 locations | NavHost, SetupShareNavigation, ChatNavigation |
| Insufficient deep link validation | HIGH | 1 location | MainActivity.kt |
| Unchecked JSON in navigation | HIGH | 2 locations | Screen.kt, ChatNavigation.kt |
| Bare exception catching | MEDIUM | 5 locations | Multiple files |
| Hard-coded route strings | MEDIUM | 1 location | NavHost.kt |
| Magic string keys | MEDIUM | 15+ locations | Multiple files |
| Unsafe URI.parse() | MEDIUM | 3 locations | ChatNavigation.kt |
| Activity.recreate() misuse | MEDIUM | 1 location | MainActivity.kt |
| Complex state coordination | MEDIUM | 1 location | NavHost.kt |
| Missing result handling | LOW | 2 patterns | Multiple files |
