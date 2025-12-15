# Automatic ETA Sharing for Saved Destinations

## Overview

Allow users to configure automatic ETA sharing for specific destinations. When navigation to a saved destination is detected, ETA will automatically be shared with pre-configured contacts without user intervention.

## User Story

> "When I start navigating to Home, automatically share my ETA with my wife and parents. When I navigate to Work, share with my wife only."

---

## UX Flow

### 1. Setup Flow (ETA Settings → Auto-Share Rules)

```
ETA Sharing Settings
└── "Auto-Share Rules" section
    └── + Add Rule
        ├── Destination: [Home / Work / Custom Address]
        ├── Share with: [Contact Picker - multi-select]
        └── Save
```

**UI Mockup:**
```
┌─────────────────────────────────────────┐
│ ETA SHARING                             │
├─────────────────────────────────────────┤
│ [x] Enable ETA Sharing                  │
│                                         │
│ ─── UPDATE SENSITIVITY ───              │
│ Notify when ETA changes by 5+ minutes   │
│ [━━━━━○━━━━━━━━━━━━━━━━━━━━]            │
│                                         │
│ ─── AUTO-SHARE RULES ───                │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ 🏠 Home                              │ │
│ │ Share with: Mom, Dad, Sarah         │ │
│ │                        [Edit] [✕]   │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ 🏢 Work                              │ │
│ │ Share with: Sarah                   │ │
│ │                        [Edit] [✕]   │ │
│ └─────────────────────────────────────┘ │
│                                         │
│         [+ Add Auto-Share Rule]         │
│                                         │
└─────────────────────────────────────────┘
```

### 2. Add/Edit Rule Dialog

```
┌─────────────────────────────────────────┐
│ Add Auto-Share Rule                     │
├─────────────────────────────────────────┤
│                                         │
│ Destination                             │
│ ┌─────────────────────────────────────┐ │
│ │ 🏠 Home                          ▼  │ │
│ └─────────────────────────────────────┘ │
│ [Home] [Work] [+ Custom]                │
│                                         │
│ Share with                              │
│ ┌─────────────────────────────────────┐ │
│ │ [Sarah ✕] [Mom ✕] [+ Add]          │ │
│ └─────────────────────────────────────┘ │
│                                         │
│         [Cancel]        [Save]          │
│                                         │
└─────────────────────────────────────────┘
```

### 3. Triggered Flow (During Navigation)

When navigation to a saved destination is detected:

```
┌─────────────────────────────────────────┐
│ 📍 Auto-shared ETA to Sarah, Mom, Dad   │ <- Toast/Snackbar (auto-dismiss 3s)
└─────────────────────────────────────────┘
```

**Persistent Notification:**
```
┌─────────────────────────────────────────┐
│ 📍 Sharing ETA with Sarah, Mom, Dad     │
│ Navigating to Home • 15 min             │
│                          [Stop Sharing] │
└─────────────────────────────────────────┘
```

### 4. In-Chat Banner (When viewing a chat that's receiving ETA)

```
┌─────────────────────────────────────────┐
│ 📍 Sharing your ETA to Home with Sarah  │
│ Auto-sharing enabled         [Stop]     │
└─────────────────────────────────────────┘
```

### 5. Stop Sharing Options

User can stop sharing via:
1. **Persistent notification** - "Stop Sharing" button
2. **In-chat banner** - "Stop" button
3. **ETA Settings** - Toggle off auto-share for that session

When stopped mid-trip:
- Send "Stopped sharing location" message to all recipients
- Don't disable the auto-share rule (will trigger again next time)

---

## Data Model

### New Entity: `AutoShareRuleEntity`

```kotlin
@Entity(tableName = "auto_share_rules")
data class AutoShareRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Destination matching
    val destinationName: String,        // "Home", "Work", or custom label
    val destinationAddress: String?,    // Full address for fuzzy matching
    val locationType: String,           // "home", "work", "custom"

    // Enabled state
    val enabled: Boolean = true,

    // Timestamps
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null
)
```

### New Entity: `AutoShareRecipientEntity`

```kotlin
@Entity(
    tableName = "auto_share_recipients",
    foreignKeys = [ForeignKey(
        entity = AutoShareRuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AutoShareRecipientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleId: Long,
    val chatGuid: String,           // The chat to share with
    val displayName: String         // Cached display name for UI
)
```

### Domain Model

```kotlin
data class AutoShareRule(
    val id: Long,
    val destinationName: String,
    val destinationAddress: String?,
    val locationType: LocationType,
    val recipients: List<AutoShareRecipient>,
    val enabled: Boolean,
    val lastTriggeredAt: Long?
)

data class AutoShareRecipient(
    val chatGuid: String,
    val displayName: String
)

enum class LocationType {
    HOME,
    WORK,
    CUSTOM
}
```

---

## Architecture Changes

### 1. New Repository: `AutoShareRuleRepository`

```kotlin
interface AutoShareRuleRepository {
    fun getAllRules(): Flow<List<AutoShareRule>>
    suspend fun getRuleForDestination(destination: String): AutoShareRule?
    suspend fun createRule(rule: AutoShareRule): Long
    suspend fun updateRule(rule: AutoShareRule)
    suspend fun deleteRule(ruleId: Long)
    suspend fun addRecipient(ruleId: Long, chatGuid: String, displayName: String)
    suspend fun removeRecipient(ruleId: Long, chatGuid: String)
    suspend fun markTriggered(ruleId: Long)
}
```

### 2. Updated `EtaSharingManager`

Add auto-share detection:

```kotlin
class EtaSharingManager @Inject constructor(
    private val pendingMessageRepository: PendingMessageRepository,
    private val settingsDataStore: SettingsDataStore,
    private val autoShareRuleRepository: AutoShareRuleRepository  // NEW
) {
    // Track active multi-recipient sessions
    private val _activeAutoShareSession = MutableStateFlow<AutoShareSession?>(null)

    data class AutoShareSession(
        val rule: AutoShareRule,
        val recipientSessions: List<EtaSharingSession>,
        val startedAt: Long
    )

    /**
     * Called when navigation is detected - checks for auto-share rules
     */
    suspend fun onNavigationDetected(eta: ParsedEtaData) {
        val destination = eta.destination ?: return

        // Check if auto-share is enabled globally
        if (!settingsDataStore.etaSharingEnabled.first()) return

        // Find matching rule
        val matchingRule = autoShareRuleRepository.getRuleForDestination(destination)
        if (matchingRule == null || !matchingRule.enabled) return

        // Start auto-share session
        startAutoShareSession(matchingRule, eta)
    }

    private suspend fun startAutoShareSession(rule: AutoShareRule, eta: ParsedEtaData) {
        // Create sessions for each recipient
        val sessions = rule.recipients.map { recipient ->
            EtaSharingSession(
                recipientGuid = recipient.chatGuid,
                recipientDisplayName = recipient.displayName,
                destination = eta.destination,
                lastEtaMinutes = eta.etaMinutes
            )
        }

        _activeAutoShareSession.value = AutoShareSession(
            rule = rule,
            recipientSessions = sessions,
            startedAt = System.currentTimeMillis()
        )

        // Notify UI about auto-share trigger
        _autoShareTriggered.emit(AutoShareTriggeredEvent(
            destinationName = rule.destinationName,
            recipientNames = rule.recipients.map { it.displayName }
        ))

        // Send initial message to all recipients
        sessions.forEach { session ->
            sendMessageForType(session, eta, EtaMessageType.INITIAL)
        }

        // Update notification
        updateAutoShareNotification(rule, eta)

        // Mark rule as triggered
        autoShareRuleRepository.markTriggered(rule.id)
    }
}
```

### 3. Notification Update

Update `NavigationListenerService` to show multi-recipient notification:

```kotlin
private fun showAutoShareNotification(
    destinationName: String,
    recipientNames: List<String>,
    etaMinutes: Int
) {
    val namesText = when {
        recipientNames.size == 1 -> recipientNames[0]
        recipientNames.size == 2 -> "${recipientNames[0]} and ${recipientNames[1]}"
        else -> "${recipientNames.take(2).joinToString(", ")} +${recipientNames.size - 2} more"
    }

    val notification = NotificationCompat.Builder(context, CHANNEL_ETA_SHARING)
        .setSmallIcon(R.drawable.ic_navigation)
        .setContentTitle("Sharing ETA with $namesText")
        .setContentText("Navigating to $destinationName • $etaMinutes min")
        .setOngoing(true)
        .addAction(/* Stop Sharing action */)
        .build()
}
```

---

## UI Components

### 1. `AutoShareRulesSection.kt`

New composable for the settings screen:

```kotlin
@Composable
fun AutoShareRulesSection(
    rules: List<AutoShareRule>,
    onAddRule: () -> Unit,
    onEditRule: (AutoShareRule) -> Unit,
    onDeleteRule: (AutoShareRule) -> Unit,
    onToggleRule: (AutoShareRule, Boolean) -> Unit
)
```

### 2. `AddAutoShareRuleDialog.kt`

Dialog for creating/editing rules:

```kotlin
@Composable
fun AddAutoShareRuleDialog(
    existingRule: AutoShareRule?,
    availableChats: List<ChatInfo>,
    onSave: (AutoShareRule) -> Unit,
    onDismiss: () -> Unit
)
```

### 3. Updated `EtaSharingBanner.kt`

Show auto-share info in chat:

```kotlin
@Composable
fun EtaSharingBanner(
    isSharing: Boolean,
    isAutoShare: Boolean,  // NEW
    destination: String?,
    recipientCount: Int,   // NEW - for multi-recipient display
    onStop: () -> Unit,
    modifier: Modifier
)
```

---

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `data/local/db/entity/AutoShareRuleEntity.kt` | Create | Database entity |
| `data/local/db/entity/AutoShareRecipientEntity.kt` | Create | Database entity |
| `data/local/db/dao/AutoShareRuleDao.kt` | Create | DAO for rules |
| `data/repository/AutoShareRuleRepository.kt` | Create | Repository interface & impl |
| `di/DatabaseModule.kt` | Modify | Add DAO |
| `services/eta/EtaSharingManager.kt` | Modify | Add auto-share logic |
| `services/eta/NavigationListenerService.kt` | Modify | Update notification |
| `ui/settings/eta/AutoShareRulesSection.kt` | Create | Settings UI |
| `ui/settings/eta/AddAutoShareRuleDialog.kt` | Create | Add/Edit dialog |
| `ui/settings/eta/EtaSharingSettingsScreen.kt` | Modify | Add rules section |
| `ui/settings/eta/EtaSharingSettingsViewModel.kt` | Modify | Add rules state |
| `ui/chat/components/EtaSharingBanner.kt` | Modify | Show auto-share state |

---

## Edge Cases & Considerations

### 1. Destination Matching

How to match navigation destination to saved rules?

**Proposed approach:**
- Use fuzzy matching (same as existing `isSameDestination()`)
- Match on destination name first (e.g., "Home" == "Home")
- Fall back to address matching if name doesn't match
- Allow user to add multiple aliases for a destination

### 2. Multiple Rules Matching

What if navigation destination matches multiple rules?

**Proposed approach:**
- Apply ALL matching rules (share with all unique recipients)
- Dedupe recipients across rules

### 3. Already Sharing Manually

What if user is already manually sharing ETA when auto-share triggers?

**Proposed approach:**
- Add the auto-share recipients to the existing session
- Show notification with combined recipient list
- Manual share takes precedence for existing recipient

### 4. Contacts API for Recipients

Where do recipient contacts come from?

**Proposed approach:**
- Show list of recent/frequent chats
- Allow search for any chat
- Store chatGuid (not phone number) for reliability

### 5. Rate Limiting

Prevent spam if navigation rapidly starts/stops?

**Proposed approach:**
- Don't re-trigger auto-share if same rule triggered in last 5 minutes
- Show "Already sharing with X" instead

---

## Design Decisions (Reviewed)

1. **Destination sources**: Manual entry for V1. User enters the string they expect to see (e.g., "Home"). Phase 2 could scrape from contact card.

2. **Rule priority**: Manual wins but coexists. If user manually shares with Bob and auto-share triggers for Alice, both receive updates. No double messages to same recipient.

3. **Disable per-session**: Per-session only. "Stop" kills current session, NOT the rule. User must go to Settings to disable rule permanently.

4. **Initial message wording**: Distinct for auto-share: "📍 Automatically sharing my ETA to Home!" - lets recipient know user didn't manually type this.

5. **Recipient limits**: 5 max per rule (suggested).

6. **Keyword matching**: Allow multiple keywords per destination (e.g., "Home" OR "123 Main St" triggers same rule). Normalize strings before matching.

7. **Privacy reminder**: After 5 consecutive days of auto-sharing to same person, show subtle reminder toast.

---

## Estimated Scope

| Phase | Work | Estimate |
|-------|------|----------|
| 1. Data layer | Entities, DAO, Repository | ~100 lines |
| 2. Manager updates | Auto-share detection & multi-recipient | ~150 lines |
| 3. Settings UI | Rules list, add/edit dialog | ~300 lines |
| 4. Notification updates | Multi-recipient display | ~50 lines |
| 5. Banner updates | Auto-share state in chat | ~30 lines |
| **Total** | | **~630 lines** |
