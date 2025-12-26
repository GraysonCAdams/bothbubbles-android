# Auto-Responder Feature (Hem) Implementation Plan

## Overview

Transform the current auto-responder service into a full-fledged Feature (Hem) with rule-based configuration. Users can create multiple auto-response rules with various trigger conditions.

## Clarifications Needed (Please Review)

Before implementation, please confirm these design decisions:

1. **Rule evaluation**: Plan assumes **first matching rule wins** (user orders rules by priority in UI)
2. **Response destination**: Plan assumes **same Stitch as incoming message** (SMS→SMS, iMessage→iMessage)
3. **"First time from sender"**: Plan assumes **first message ever from this sender on selected Stitch(es)** since app install
4. **Location**: Plan uses **simple address/place name with geocoding** (creates geofence internally)

---

## Architecture

### New Files to Create

```
app/src/main/kotlin/com/bothbubbles/
├── seam/hems/autoresponder/
│   ├── AutoResponderFeature.kt          # Feature interface implementation
│   ├── AutoResponderRuleEngine.kt       # Rule evaluation logic
│   └── AutoResponderConditionEvaluator.kt # Evaluates individual conditions
│
├── data/local/db/
│   ├── entity/ (add to existing)
│   │   └── AutoResponderRuleEntity.kt   # Rule storage entity
│   ├── dao/ (add to existing)
│   │   └── AutoResponderRuleDao.kt      # CRUD for rules
│
├── services/autoresponder/
│   └── (modify AutoResponderService.kt) # Integrate with rule engine
│
├── services/context/                     # NEW - System context providers
│   ├── DndStateProvider.kt              # Do Not Disturb state
│   ├── CallStateProvider.kt             # Phone call state
│   └── LocationStateProvider.kt         # Location/geofence state
│
├── ui/settings/autoresponder/
│   ├── (modify existing)
│   ├── AutoResponderRulesScreen.kt      # Rule list management
│   ├── AutoResponderRuleEditorScreen.kt # Create/edit individual rule
│   └── AutoResponderRuleEditorViewModel.kt
```

### Files to Modify

1. `di/FeatureModule.kt` - Add AutoResponderFeature binding
2. `di/DatabaseModule.kt` - Add AutoResponderRuleDao
3. `data/local/db/BothBubblesDatabase.kt` - Add entity + dao + migration
4. `core/data/prefs/FeaturePreferences.kt` - Already has autoResponderEnabled, may need adjustments
5. `services/autoresponder/AutoResponderService.kt` - Integrate with rule engine
6. `ui/settings/autoresponder/AutoResponderSettingsScreen.kt` - New rule-based UI
7. `seam/stitches/Stitch.kt` - Add `autoResponderQuickAddExample` property
8. `seam/stitches/bluebubbles/BlueBubblesStitch.kt` - Provide current message as example
9. `seam/stitches/sms/SmsStitch.kt` - Optionally provide SMS example

---

## Data Model

### AutoResponderRuleEntity

```kotlin
@Entity(tableName = "auto_responder_rules")
data class AutoResponderRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,                    // User-defined rule name
    val message: String,                 // Response message template
    val priority: Int,                   // Lower = higher priority (for ordering)
    val isEnabled: Boolean = true,

    // Conditions (all nullable - null means "don't check this condition")
    val sourceStitchIds: String?,        // Comma-separated Stitch IDs (e.g., "sms,bluebubbles")
    val firstTimeFromSender: Boolean?,   // true = only first message ever from sender

    // Time-based conditions
    val daysOfWeek: String?,             // Comma-separated: "MON,TUE,WED,THU,FRI,SAT,SUN"
    val timeStartMinutes: Int?,          // Start time in minutes from midnight (e.g., 540 = 9:00 AM)
    val timeEndMinutes: Int?,            // End time in minutes from midnight

    // System state conditions
    val dndModes: String?,               // Comma-separated DND modes: "PRIORITY_ONLY,ALARMS_ONLY,TOTAL_SILENCE"
    val requireDriving: Boolean?,        // true = only when connected to Android Auto
    val requireOnCall: Boolean?,         // true = only when on a phone call

    // Location conditions
    val locationName: String?,           // Human-readable place name
    val locationLat: Double?,            // Latitude for geofence center
    val locationLng: Double?,            // Longitude for geofence center
    val locationRadiusMeters: Int?,      // Geofence radius (default 100m)
    val locationInside: Boolean?,        // true = inside geofence, false = outside

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### Condition Types (Sealed Class)

```kotlin
sealed interface AutoResponderCondition {
    data class SourceStitch(val stitchIds: Set<String>) : AutoResponderCondition
    data object FirstTimeFromSender : AutoResponderCondition
    data class DayOfWeek(val days: Set<DayOfWeek>) : AutoResponderCondition
    data class TimeRange(val startMinutes: Int, val endMinutes: Int) : AutoResponderCondition
    data class DndMode(val modes: Set<DndModeType>) : AutoResponderCondition
    data object Driving : AutoResponderCondition
    data object OnCall : AutoResponderCondition
    data class Location(
        val name: String,
        val lat: Double,
        val lng: Double,
        val radiusMeters: Int,
        val inside: Boolean
    ) : AutoResponderCondition
}

enum class DndModeType {
    PRIORITY_ONLY,      // INTERRUPTION_FILTER_PRIORITY
    ALARMS_ONLY,        // INTERRUPTION_FILTER_ALARMS
    TOTAL_SILENCE       // INTERRUPTION_FILTER_NONE
}
```

---

## Stitch Quick-Add Examples

### Add to Stitch Interface

```kotlin
interface Stitch {
    // ... existing properties ...

    /**
     * Optional quick-add example for auto-responder.
     * Returns a pre-configured rule that users can quickly add.
     * Return null if this Stitch doesn't have a suggested auto-response.
     */
    val autoResponderQuickAddExample: AutoResponderQuickAdd?
        get() = null
}

data class AutoResponderQuickAdd(
    val ruleName: String,
    val description: String,
    val message: String,
    val defaultConditions: List<AutoResponderCondition>
)
```

### BlueBubblesStitch Example

```kotlin
override val autoResponderQuickAddExample = AutoResponderQuickAdd(
    ruleName = "iMessage Redirect",
    description = "Help SMS contacts switch to iMessage",
    message = "Hello! I use iMessage on my Android via BlueBubbles. " +
        "Please add my iMessage address to my contact so future messages go through iMessage.",
    defaultConditions = listOf(
        AutoResponderCondition.SourceStitch(setOf("sms")),  // Only for SMS messages
        AutoResponderCondition.FirstTimeFromSender          // Only first message
    )
)
```

**Note**: Removed auto phone number generation per user request.

---

## Rule Engine Logic

### AutoResponderRuleEngine

```kotlin
@Singleton
class AutoResponderRuleEngine @Inject constructor(
    private val ruleDao: AutoResponderRuleDao,
    private val conditionEvaluator: AutoResponderConditionEvaluator
) {
    /**
     * Find the first matching rule for the given context.
     * Rules are evaluated in priority order (lower priority number = checked first).
     */
    suspend fun findMatchingRule(context: MessageContext): AutoResponderRuleEntity? {
        val enabledRules = ruleDao.getEnabledRulesByPriority()

        for (rule in enabledRules) {
            if (conditionEvaluator.allConditionsMet(rule, context)) {
                return rule
            }
        }
        return null
    }
}

data class MessageContext(
    val senderAddress: String,
    val chatGuid: String,
    val stitchId: String,
    val isFirstFromSender: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
```

### AutoResponderConditionEvaluator

```kotlin
@Singleton
class AutoResponderConditionEvaluator @Inject constructor(
    private val dndStateProvider: DndStateProvider,
    private val callStateProvider: CallStateProvider,
    private val locationStateProvider: LocationStateProvider,
    private val drivingStateTracker: DrivingStateTracker  // Existing
) {
    suspend fun allConditionsMet(rule: AutoResponderRuleEntity, context: MessageContext): Boolean {
        // Source Stitch check
        rule.sourceStitchIds?.let { ids ->
            val allowedIds = ids.split(",").map { it.trim() }.toSet()
            if (context.stitchId !in allowedIds) return false
        }

        // First time from sender
        if (rule.firstTimeFromSender == true && !context.isFirstFromSender) {
            return false
        }

        // Day of week
        rule.daysOfWeek?.let { days ->
            val today = LocalDate.now().dayOfWeek.name.take(3).uppercase()
            if (today !in days.split(",")) return false
        }

        // Time range
        if (rule.timeStartMinutes != null && rule.timeEndMinutes != null) {
            val nowMinutes = LocalTime.now().toSecondOfDay() / 60
            if (!isInTimeRange(nowMinutes, rule.timeStartMinutes, rule.timeEndMinutes)) {
                return false
            }
        }

        // DND mode
        rule.dndModes?.let { modes ->
            val currentDnd = dndStateProvider.getCurrentDndMode()
            if (currentDnd?.name !in modes.split(",")) return false
        }

        // Driving (Android Auto)
        if (rule.requireDriving == true && !drivingStateTracker.isCarConnected.value) {
            return false
        }

        // On a call
        if (rule.requireOnCall == true && !callStateProvider.isOnCall()) {
            return false
        }

        // Location
        if (rule.locationLat != null && rule.locationLng != null) {
            val isInside = locationStateProvider.isInsideGeofence(
                rule.locationLat, rule.locationLng, rule.locationRadiusMeters ?: 100
            )
            if (rule.locationInside == true && !isInside) return false
            if (rule.locationInside == false && isInside) return false
        }

        return true
    }
}
```

---

## System Context Providers

### DndStateProvider

```kotlin
@Singleton
class DndStateProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun getCurrentDndMode(): DndModeType? {
        return when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndModeType.PRIORITY_ONLY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndModeType.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndModeType.TOTAL_SILENCE
            else -> null  // INTERRUPTION_FILTER_ALL = DND is off
        }
    }
}
```

### CallStateProvider

```kotlin
@Singleton
class CallStateProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    fun isOnCall(): Boolean {
        // Requires READ_PHONE_STATE permission
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }
}
```

### LocationStateProvider

```kotlin
@Singleton
class LocationStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun isInsideGeofence(lat: Double, lng: Double, radiusMeters: Int): Boolean {
        // Get last known location and check distance
        return suspendCancellableCoroutine { cont ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) {
                        cont.resume(false)
                    } else {
                        val results = FloatArray(1)
                        Location.distanceBetween(
                            location.latitude, location.longitude,
                            lat, lng, results
                        )
                        cont.resume(results[0] <= radiusMeters)
                    }
                }
                .addOnFailureListener {
                    cont.resume(false)
                }
        }
    }
}
```

---

## UI Design

### Main Auto-Responder Settings Screen

```
┌────────────────────────────────────────┐
│ ← Auto-Responder                       │
├────────────────────────────────────────┤
│ ┌────────────────────────────────────┐ │
│ │ 🤖 Automated Responses             │ │
│ │ Send automatic replies based on    │ │
│ │ customizable rules and conditions  │ │
│ └────────────────────────────────────┘ │
│                                        │
│ [============================] ON      │
│                                        │
│ ─────────────────────────────────────  │
│ YOUR RULES                             │
│ ─────────────────────────────────────  │
│ ┌─ iMessage Redirect ────────────┐    │
│ │ SMS · First time only     [ON] │ ⋮  │
│ └────────────────────────────────┘    │
│ ┌─ Driving Response ─────────────┐    │
│ │ Android Auto              [ON] │ ⋮  │
│ └────────────────────────────────┘    │
│ ┌─ Night Mode ───────────────────┐    │
│ │ 10PM - 7AM · DND         [OFF] │ ⋮  │
│ └────────────────────────────────┘    │
│                                        │
│           [+ Add Rule]                 │
│                                        │
│ ─────────────────────────────────────  │
│ QUICK ADD                              │
│ ─────────────────────────────────────  │
│ ┌─ iMessage ─────────────────────┐    │
│ │ 💬 iMessage Redirect           │    │
│ │ Help SMS contacts switch...    │ +  │
│ └────────────────────────────────┘    │
│                                        │
│ ─────────────────────────────────────  │
│ RATE LIMIT                             │
│ Maximum 10 auto-responses per hour     │
│ ═══════════○═══════════════════════    │
└────────────────────────────────────────┘
```

### Rule Editor Screen

```
┌────────────────────────────────────────┐
│ ← Edit Rule                     [Save] │
├────────────────────────────────────────┤
│ Rule Name                              │
│ ┌────────────────────────────────────┐ │
│ │ iMessage Redirect                  │ │
│ └────────────────────────────────────┘ │
│                                        │
│ Message                                │
│ ┌────────────────────────────────────┐ │
│ │ Hello! I use iMessage on my        │ │
│ │ Android via BlueBubbles...         │ │
│ └────────────────────────────────────┘ │
│                                        │
│ ─────────────────────────────────────  │
│ CONDITIONS                             │
│ ─────────────────────────────────────  │
│                                        │
│ Message Source                         │
│ ┌──────┐ ┌──────────┐                 │
│ │ SMS ✓│ │ iMessage │                 │
│ └──────┘ └──────────┘                 │
│                                        │
│ First time from sender          [ON]   │
│                                        │
│ ─────────────────────────────────────  │
│ ▸ Time & Day Conditions                │
│ ▸ System State Conditions              │
│ ▸ Location Conditions                  │
│                                        │
└────────────────────────────────────────┘
```

---

## Implementation Steps

### Phase 1: Core Infrastructure (Database + Feature)
1. Create `AutoResponderRuleEntity` and migration
2. Create `AutoResponderRuleDao`
3. Create `AutoResponderFeature` implementing Feature interface
4. Add binding in `FeatureModule.kt`
5. Create system context providers (DndStateProvider, CallStateProvider, LocationStateProvider)

### Phase 2: Rule Engine
6. Create `AutoResponderConditionEvaluator`
7. Create `AutoResponderRuleEngine`
8. Modify `AutoResponderService` to use rule engine

### Phase 3: Stitch Integration
9. Add `autoResponderQuickAddExample` to `Stitch` interface
10. Implement in `BlueBubblesStitch` (current message without phone number)
11. Optionally implement in `SmsStitch`

### Phase 4: UI
12. Create `AutoResponderRulesScreen` (list view)
13. Create `AutoResponderRuleEditorScreen` + ViewModel
14. Modify existing `AutoResponderSettingsScreen` to use new screens
15. Add navigation routes

### Phase 5: Testing & Polish
16. Unit tests for condition evaluator
17. Integration tests for rule engine
18. UI tests for rule editor
19. Permission handling for location/phone state

---

## Permissions Required

```xml
<!-- For DND state -->
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />

<!-- For call state -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />

<!-- For location (if using location conditions) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

---

## Database Migration

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS auto_responder_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                message TEXT NOT NULL,
                priority INTEGER NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                sourceStitchIds TEXT,
                firstTimeFromSender INTEGER,
                daysOfWeek TEXT,
                timeStartMinutes INTEGER,
                timeEndMinutes INTEGER,
                dndModes TEXT,
                requireDriving INTEGER,
                requireOnCall INTEGER,
                locationName TEXT,
                locationLat REAL,
                locationLng REAL,
                locationRadiusMeters INTEGER,
                locationInside INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
    }
}
```

---

## Notes

1. **Backwards compatibility**: Existing auto-responder preference (`autoResponderEnabled`) will control the Feature's enabled state. Existing settings (filter, rate limit, alias) can be migrated to a default rule on first launch.

2. **Rate limiting**: Keep the existing rate limit logic but apply it across ALL rules combined (not per-rule).

3. **"Responded to sender" tracking**: The existing `AutoRespondedSenderDao` continues to work, but now tracks per-rule responses (need to add `ruleId` column).

4. **DrivingStateTracker**: Already exists and can be reused directly.

5. **Quick-add examples**: Only shown for enabled Stitches that provide them. User can tap to instantly create a pre-configured rule.
