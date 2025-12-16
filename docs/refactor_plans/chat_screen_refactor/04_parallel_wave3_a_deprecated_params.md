# Wave 3A (Parallel): Remove Deprecated Parameters

**Prerequisites**:
- Read `00_shared_conventions.md` first
- Wave 2 (sequential ChatScreen cutover) must be complete

**Owned Files**: `ChatInputUI.kt`, `ChatMessageList.kt`, `ChatDialogsHost.kt`, `ChatTopBar.kt`, `AnimatedThreadOverlay.kt`

---

## Objective

Remove all `@Deprecated` parameters that were added during Wave 1 for backward compatibility.

---

## Tasks

### 1. ChatInputUI.kt

Remove deprecated parameters:

```kotlin
// REMOVE these parameters:
@Deprecated("Collected internally from sendDelegate")
sendState: SendState? = null,
@Deprecated("Collected internally from composerDelegate")
smartReplySuggestions: List<SuggestionItem>? = null,
@Deprecated("Collected internally from composerDelegate")
activePanelState: ActivePanelState? = null,
@Deprecated("Collected internally from composerDelegate")
gifPickerState: GifPickerState? = null,
@Deprecated("Collected internally from composerDelegate")
gifSearchQuery: String? = null,
```

Remove fallback logic:

```kotlin
// REMOVE fallback variables like:
val effectiveSendState = sendStateInternal ?: sendState

// SIMPLIFY to direct collection:
val sendState by sendDelegate.state.collectAsStateWithLifecycle()
```

### 2. ChatMessageList.kt

Remove deprecated parameters:

```kotlin
// REMOVE these parameters:
@Deprecated("Collected internally from searchDelegate")
searchState: SearchState? = null,
@Deprecated("Collected internally from syncDelegate")
syncState: SyncState? = null,
@Deprecated("Collected internally from operationsDelegate")
operationsState: OperationsState? = null,
@Deprecated("Collected internally from effectsDelegate")
effectsState: EffectsState? = null,
@Deprecated("Collected internally from etaSharingDelegate")
etaSharingState: EtaSharingState? = null,
@Deprecated("Collected internally from messageListDelegate")
isLoadingFromServer: Boolean? = null,
@Deprecated("Collected internally from messageListDelegate")
initialLoadComplete: Boolean? = null,
@Deprecated("Collected internally from attachmentDelegate")
autoDownloadEnabled: Boolean? = null,
@Deprecated("Collected internally from attachmentDelegate")
downloadingAttachments: Map<String, Float>? = null,
```

### 3. ChatDialogsHost.kt

Remove deprecated parameters:

```kotlin
// REMOVE these parameters:
@Deprecated("Collected internally from connectionDelegate")
connectionState: ConnectionState? = null,
@Deprecated("Collected internally via viewModel.getForwardableChats()")
forwardableChats: List<ChatUiModel>? = null,
```

### 4. ChatTopBar.kt

Remove deprecated parameters:

```kotlin
// REMOVE these parameters:
@Deprecated("Collected internally from operationsDelegate")
operationsState: OperationsState? = null,
@Deprecated("Collected internally from chatInfoDelegate")
infoState: ChatInfoState? = null,
```

### 5. AnimatedThreadOverlay.kt

Remove deprecated parameters:

```kotlin
// REMOVE these parameters:
@Deprecated("Collected internally from threadDelegate")
threadOverlayState: ThreadOverlayState? = null,
```

---

## Verification

- [ ] No `@Deprecated` parameters remain in any of the owned files
- [ ] No `effective*` fallback variables remain
- [ ] All state is collected directly from delegates
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] No unused parameter warnings

---

## Notes

- This is a cleanup task - should be straightforward removals
- If build fails, check that Wave 2 (ChatScreen cutover) properly stopped passing these parameters
