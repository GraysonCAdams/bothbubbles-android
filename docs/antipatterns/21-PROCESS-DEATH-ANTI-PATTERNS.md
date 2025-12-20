# State Restoration and Process Death Anti-Patterns

**Scope:** SavedStateHandle, rememberSaveable, configuration changes

---

## High Severity Issues

### 1. Dialog State Not Surviving Process Death

**Location:** `ChatScreenState.kt` (lines 37-52)

**Issue:** ~15 dialog visibility flags use `mutableStateOf` without SavedStateHandle:
```kotlin
var showDeleteDialog by mutableStateOf(false)
var showBlockDialog by mutableStateOf(false)
var showVideoCallDialog by mutableStateOf(false)
// ... 10+ more dialog flags
```

**Problem:** All dialog states lost on rotation or process death.

**Fix:**
```kotlin
private val _showDeleteDialog = savedStateHandle.getStateFlow("show_delete_dialog", false)
val showDeleteDialog: StateFlow<Boolean> = _showDeleteDialog
```

---

### 2. Form Input State Not Persisting

**Locations:**
- `ComposerTextField.kt` (line 93)
- `Life360SettingsScreen.kt` (lines 191, 566)

**Issue:**
```kotlin
var textFieldValue by remember { mutableStateOf(TextFieldValue(text)) }
var manualToken by remember { mutableStateOf("") }
```

**Problem:** User types long message → rotates → message lost.

**Fix:**
```kotlin
var textFieldValue by rememberSaveable(
    saver = TextFieldValue.Saver,
    init = { TextFieldValue(text) }
) { mutableStateOf(TextFieldValue(text)) }
```

---

## Medium Severity Issues

### 3. Missing rememberSaveable for UI State

**Locations:**
- `DeveloperEventLogScreen.kt` - Dialog selection state
- `AutoResponderSettingsScreen.kt` - Dropdown expanded state
- `Life360LoginWebView.kt` (lines 40-42) - WebView state
- `ActionSelectionCard.kt` - Dropdown state

**Issue:**
```kotlin
var selectedEvent by remember { mutableStateOf<DeveloperEvent?>(null) }
var expanded by remember { mutableStateOf(false) }
```

**Fix:** Replace `remember` with `rememberSaveable`.

---

### 4. LazyColumn Scroll Position Not Saved

**Locations:**
- `Life360SettingsScreen.kt` - Member list
- `AttachmentThumbnailRow.kt` (line 129)
- `DeveloperEventLogScreen.kt`
- `ConversationDetailsScreen.kt` (line 73)

**Issue:**
```kotlin
val listState = rememberLazyListState()  // Not persisted
```

**Fix:**
```kotlin
// In ViewModel
private val _scrollIndex = savedStateHandle.getStateFlow("scroll_index", 0)

// In Composable
val listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollIndex)
LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex }
        .collect { _scrollIndex.value = it }
}
```

---

### 5. ChatScreenState Not Backed by SavedStateHandle

**Location:** `ChatScreenState.kt` (lines 37-100)

**Issue:** 20+ properties in ChatScreenState not persisted:
```kotlin
var selectedMessageGuids by mutableStateOf(setOf<String>())
// All selection state lost on process death
```

---

### 6. Global Singleton State Not Lifecycle-Aware

**Locations:**
- `ParticlePool.kt` (lines 78-80)
- `ComposerTutorial.kt` (line 186)
- `MentionPositionTracker.kt`

**Issue:**
```kotlin
object GlobalParticlePool {
    val instance = ParticlePool(initialSize = 200, maxSize = 1000)
}
```

**Fix:** Use dependency injection with proper lifecycle scope.

---

### 7. WebView Loading State Lost

**Location:** `Life360LoginWebView.kt` (lines 40-52)

**Issue:**
```kotlin
var isLoading by remember { mutableStateOf(true) }
var loadingProgress by remember { mutableFloatStateOf(0f) }
```

**Fix:** Use `rememberSaveable` for loading state.

---

### 8. Attachment Reordering State Lost

**Location:** `AttachmentThumbnailRow.kt` (lines 132-137)

**Issue:**
```kotlin
var draggedIndex by remember { mutableIntStateOf(-1) }
var workingItems by remember(attachments) { mutableStateOf(attachments) }
```

**Problem:** User reorders attachments → rotates → reordering lost.

**Fix:** Use StateFlow in ViewModel with SavedStateHandle.

---

## Low Severity Issues

### 9. Missing Null Checks After Restoration

**Location:** `ChatViewModel.kt` (lines 114-121)

**Issue:** `mergedGuids` processed without validation after restoration.

**Fix:** Add validation for restored GUIDs:
```kotlin
.filter { it.isNotBlank() && isValidChatGuid(it) }
```

---

## Summary Table

| Issue | Severity | Files Affected |
|-------|----------|----------------|
| Dialog state not persisting | HIGH | ChatScreenState.kt, Life360SettingsScreen.kt |
| Form input state lost | HIGH | ComposerTextField.kt, Life360SettingsScreen.kt |
| Missing rememberSaveable | MEDIUM | 4+ screens |
| LazyColumn scroll not saved | MEDIUM | 4+ screens |
| ChatScreenState not backed | MEDIUM | ChatScreenState.kt |
| Global singleton state | MEDIUM | ParticlePool.kt, ComposerTutorial.kt |
| WebView state lost | MEDIUM | Life360LoginWebView.kt |
| Attachment reorder lost | MEDIUM | AttachmentThumbnailRow.kt |
| Missing null checks | LOW | ChatViewModel.kt |

---

## Priority Order for Fixes

1. **Immediate (HIGH):**
   - Migrate ChatScreenState dialog flags to SavedStateHandle
   - Use `rememberSaveable` for form inputs

2. **High Priority (MEDIUM):**
   - Implement scroll position restoration for LazyColumns
   - Make global singletons lifecycle-aware
   - Protect WebView loading state

3. **Medium Priority:**
   - Implement attachment reordering persistence
   - Add validation for restored data
