# Navigation Anti-Patterns

**Scope:** Compose Navigation, deep links, back stack, state

---

## High Severity Issues

### 1. Unsafe Back Stack Entry Retrieval ✅ FIXED

**Status:** Fixed in commit [current]

**Locations:**
- `NavHost.kt` (lines 101-102, 117-118) - Fixed
- `SetupShareNavigation.kt` (lines 44, 65) - Fixed
- `ChatNavigation.kt` (line 277) - Fixed

**Issue:**
```kotlin
navController.navigate(chatRoute) { ... }
// Immediately after:
val chatEntry = navController.getBackStackEntry(chatRoute)  // May not exist yet!
```

**Problem:** Back stack entry may not exist immediately after navigate().

**Fix Applied:** Now uses `navController.currentBackStackEntry` with null-safe operators and Timber logging.

---

### 2. Insufficient Deep Link Validation ✅ FIXED

**Status:** Fixed in commit [current]

**Location:** `MainActivity.kt` (lines 249-261) - Fixed

**Issue:**
```kotlin
val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID) ?: return null
// No validation for empty strings, format, or existence
```

**Fix Applied:**
```kotlin
val chatGuid = intent.getStringExtra(EXTRA_CHAT_GUID)
    ?.takeIf { it.isNotBlank() } ?: return null
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

### 6. Magic String Keys in SavedStateHandle ✅ FIXED

**Status:** Fixed in commit [current]

**Locations:** Multiple files - All fixed

**Duplicated Keys:**
- `"open_settings_panel"` - NavHost.kt, ChatNavigation.kt
- `"restore_scroll_position"` - NavHost.kt, ChatNavigation.kt
- `"shared_text"`, `"shared_uris"` - Multiple locations

**Fix Applied:** Created `NavigationKeys` object in `ui/navigation/NavigationKeys.kt`:
```kotlin
object NavigationKeys {
    const val RESTORE_SCROLL_POSITION = "restore_scroll_position"
    const val RESTORE_SCROLL_OFFSET = "restore_scroll_offset"
    const val ACTIVATE_SEARCH = "activate_search"
    const val CAPTURED_PHOTO_URI = "captured_photo_uri"
    const val SHARED_TEXT = "shared_text"
    const val SHARED_URIS = "shared_uris"
    const val EDITED_ATTACHMENT_URI = "edited_attachment_uri"
    const val EDITED_ATTACHMENT_CAPTION = "edited_attachment_caption"
    const val ORIGINAL_ATTACHMENT_URI = "original_attachment_uri"
    const val OPEN_SETTINGS_PANEL = "open_settings_panel"
}
```

All usages updated to use these constants.

---

### 7. Unsafe URI.parse Without Validation ✅ FIXED

**Status:** Fixed in commit [current]

**Location:** `ChatNavigation.kt` (lines 250, 264, 274) - Fixed

**Issue:**
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))  // No validation!
```

**Fix Applied:**
```kotlin
try {
    val uri = Uri.parse(url)
    if (uri.scheme in listOf("http", "https", "tel", "mailto")) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } else {
        Timber.w("Unsupported URI scheme: ${uri.scheme}")
    }
} catch (e: Exception) {
    Timber.w(e, "Failed to parse or open URI: $url")
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

| Issue | Severity | Status | Count | Files |
|-------|----------|--------|-------|-------|
| Unsafe back stack entry | HIGH | ✅ FIXED | 4 locations | NavHost, SetupShareNavigation, ChatNavigation |
| Insufficient deep link validation | HIGH | ✅ FIXED | 1 location | MainActivity.kt |
| Unchecked JSON in navigation | HIGH | OPEN | 2 locations | Screen.kt, ChatNavigation.kt |
| Bare exception catching | MEDIUM | PARTIALLY FIXED | 5 locations | Multiple files (improved with logging) |
| Hard-coded route strings | MEDIUM | OPEN | 1 location | NavHost.kt |
| Magic string keys | MEDIUM | ✅ FIXED | 15+ locations | Multiple files |
| Unsafe URI.parse() | MEDIUM | ✅ FIXED | 3 locations | ChatNavigation.kt |
| Activity.recreate() misuse | MEDIUM | OPEN | 1 location | MainActivity.kt |
| Complex state coordination | MEDIUM | OPEN | 1 location | NavHost.kt |
| Missing result handling | LOW | OPEN | 2 patterns | Multiple files |
