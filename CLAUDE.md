# Claude Code Instructions for BothBubbles

This file provides **mandatory rules** for Claude Code when working with this repository. These are RULES, not guidelines. Agents MUST follow them.

## Architecture Rules

The following READMEs define **mandatory patterns** for each layer. You MUST read and follow the rules in these files before writing code in that layer:

| README | Layer | Required Reading Before |
|--------|-------|------------------------|
| `app/src/main/kotlin/com/bothbubbles/README.md` | Root | Any code changes |
| `app/src/main/kotlin/com/bothbubbles/data/README.md` | Data | Repository, DAO, or data model changes |
| `app/src/main/kotlin/com/bothbubbles/services/README.md` | Services | Service or handler changes |
| `app/src/main/kotlin/com/bothbubbles/ui/README.md` | UI | Screen, ViewModel, or component changes |
| `app/src/main/kotlin/com/bothbubbles/di/README.md` | DI | Module or injection changes |
| `app/src/main/kotlin/com/bothbubbles/seam/README.md` | Seam | Stitch or Feature changes |
| `app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md` | Delegates | ViewModel delegate pattern |
| `app/schemas/README.md` | Database | Schema migrations |
| `docs/guides/COMPOSE_BEST_PRACTICES.md` | Compose | **ANY** Compose UI code |
| `docs/antipatterns/00-ANTI-PATTERNS-INDEX.md` | Anti-Patterns | **ALL** code changes (consult before implementing) |

**RULE**: When modifying code in a layer, you MUST first read that layer's README and follow its patterns exactly.

## BlueBubbles Server Reference

The `references/bluebubbles-server/` directory contains a copy of the BlueBubbles server source code. **Consult this when**:
- Debugging API response issues (what fields the server returns)
- Understanding iMessage database schema (entity definitions)
- Implementing new API endpoints or understanding existing ones

Key files:
- `packages/server/src/server/databases/imessage/entity/` - iMessage database entities (Chat, Message, Handle)
- `packages/server/src/server/api/serializers/` - How entities are serialized to JSON responses
- `packages/server/src/server/api/http/api/v1/routers/` - API route handlers
- `packages/server/src/server/types.ts` - TypeScript type definitions for API responses

## Anti-Pattern Documentation (MANDATORY READING)

The `docs/antipatterns/` directory contains **22 detailed anti-pattern reports** from a comprehensive codebase analysis. Before writing ANY code, you MUST consult the relevant anti-pattern documents:

| Document | When to Consult |
|----------|----------------|
| `01-UI-ANTI-PATTERNS.md` | Any Compose/ViewModel code |
| `02-DATA-ANTI-PATTERNS.md` | Repository, DAO, entity changes |
| `03-SERVICES-ANTI-PATTERNS.md` | Service/handler changes |
| `04-DI-UTIL-ANTI-PATTERNS.md` | DI modules or utility classes |
| `10-SECURITY-ANTI-PATTERNS.md` | Network, auth, or data handling |
| `11-CONCURRENCY-ANTI-PATTERNS.md` | Any threading/coroutine code |
| `13-ERROR-HANDLING-ANTI-PATTERNS.md` | Exception handling, Result types |
| `16-LOGGING-ANTI-PATTERNS.md` | Any Timber logging |

## Project Overview

BothBubbles is a native Android messaging app (Kotlin + Jetpack Compose) that connects to a BlueBubbles server for iMessage functionality, with optional SMS/MMS support as a fallback.

---

## CRITICAL RULES (Violations Cause Crashes/Security Issues)

### Security Rules (MANDATORY)

**RULE**: NEVER log sensitive data. The following must NEVER appear in logs:
- Auth keys, passwords, or tokens (even partial - no `password.take(4)`)
- Full phone numbers or email addresses (use `address.take(3)***` if needed)
- Message content (even previews)
- URLs containing auth parameters

**RULE**: NEVER use unsafe SSL/TLS patterns:
```kotlin
// FORBIDDEN - accepts any certificate
.hostnameVerifier { _, _ -> true }
override fun checkServerTrusted(...) {}  // Empty implementation
```

**RULE**: Remove ALL debug logging tags before committing:
- `[SEND_TRACE]`, `[LOCATION_DEBUG]`, `[FCM_DEBUG]`, `[VM_SEND]`, `[DUPLICATE_DETECT]`
- Any `Timber.d("DEBUG ...")` statements

### Concurrency Rules (MANDATORY)

**RULE**: NEVER use `SimpleDateFormat` - it is NOT thread-safe. Use `DateTimeFormatter` instead:
```kotlin
// FORBIDDEN
SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(str)

// REQUIRED
DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).parse(str)
```

**RULE**: NEVER use `runBlocking` in production code - it defeats coroutines and causes deadlocks:
```kotlin
// FORBIDDEN
runBlocking { dao.query() }

// REQUIRED - make function suspend
suspend fun doWork() { dao.query() }
```

**RULE**: Synchronize ALL operations in check-then-act patterns:
```kotlin
// FORBIDDEN - race condition between check and put
cache.get(key)?.let { return it }
val result = expensiveOperation()
cache.put(key, result)

// REQUIRED - single synchronized block
synchronized(lock) {
    cache.get(key)?.let { return it }
    val result = expensiveOperation()
    cache.put(key, result)
    return result
}
```

**RULE**: Use atomic state updates for related fields:
```kotlin
// FORBIDDEN - non-atomic
activeChatGuid = chatGuid
activeMergedGuids = mergedGuids

// REQUIRED - single atomic reference
data class ActiveState(val chatGuid: String, val mergedGuids: Set<String>)
activeState = ActiveState(chatGuid, mergedGuids)
```

### Error Handling Rules (MANDATORY)

**RULE**: NEVER use force unwrap (`!!`) on nullable collection operations:
```kotlin
// FORBIDDEN - crashes on empty list
list.minOrNull()!!
list.maxByOrNull { it.value }!!
flow.first()

// REQUIRED - safe alternatives
list.minOrNull() ?: return
list.maxByOrNull { it.value } ?: defaultValue
flow.firstOrNull() ?: emptyList()
```

**RULE**: ALWAYS use safe casts with defaults in Flow combine:
```kotlin
// FORBIDDEN - crashes when StateFlow is null during init
values[0] as Boolean

// REQUIRED - safe cast with default
values[0] as? Boolean ?: false
```

**RULE**: NEVER catch exceptions silently - always log:
```kotlin
// FORBIDDEN
} catch (_: Exception) {}
} catch (e: Exception) { /* silent */ }

// REQUIRED
} catch (e: Exception) {
    Timber.w(e, "Context about what failed")
}
```

**RULE**: NEVER show raw exception messages to users:
```kotlin
// FORBIDDEN
_uiState.update { it.copy(error = e.message) }

// REQUIRED
val userMessage = when (e) {
    is IOException -> "Network error - check your connection"
    else -> "Something went wrong"
}
_uiState.update { it.copy(error = userMessage) }
Timber.e(e, "Operation failed")  // Log technical details
```

---

## Compose Rules (MANDATORY)

**RULE**: You MUST follow the rules in `docs/guides/COMPOSE_BEST_PRACTICES.md`. Violations will break performance.

1.  **Leaf-Node State**: NEVER collect state in a parent just to pass it down. Push state collection to the lowest possible child.
2.  **Immutable Collections**: ALWAYS use `ImmutableList` / `ImmutableMap` from `kotlinx.collections.immutable` in UI state.
3.  **Stable Callbacks**: ALWAYS use method references (`viewModel::method`) instead of lambdas capturing state.
4.  **No Logic in Composition**: NEVER put logging, I/O, or complex calculations in the composition path.
5.  **Persist Scroll Positions**: Use `rememberSaveable` with built-in savers to survive process death:
    ```kotlin
    // REQUIRED for LazyColumn/LazyRow
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(state = listState) { ... }

    // REQUIRED for LazyGrid
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    LazyVerticalGrid(state = gridState) { ... }
    ```

---

## DI & Utility Rules (MANDATORY)

**RULE**: Utilities MUST be stateless. If a class:
- Has `@Inject` constructor
- Maintains mutable state
- Requires `init()` to be called
- Needs Context parameter

Then it is a **SERVICE**, not a utility. Move it to `services/` package.

**RULE**: Add `@Singleton` to dispatcher providers:
```kotlin
@Provides
@Singleton  // REQUIRED
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
```

**RULE**: Classes over 500 lines MUST be decomposed:
- ViewModels > 300 lines → use delegates
- Utilities > 500 lines → split by responsibility
- Services > 500 lines → extract handlers

---

## Logging Rules (MANDATORY)

**RULE**: ALWAYS include exception in `Timber.e()`:
```kotlin
// FORBIDDEN
Timber.e("Failed to load")

// REQUIRED
Timber.e(e, "Failed to load")
```

**RULE**: Gate verbose logging with BuildConfig.DEBUG:
```kotlin
if (BuildConfig.DEBUG) {
    Timber.d("Verbose debug info...")
}
```

**RULE**: Use consistent Timber tags for subsystems:
```kotlin
Timber.tag("Socket").d("Connected")
Timber.tag("MessageSend").d("Queued")
```

---

## Build Commands

This is a native Kotlin Android project using Gradle. **Do not use Flutter commands.**

### Prerequisites

Java must be configured. On macOS without system Java, use Android Studio's bundled JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Building

```bash
# Debug build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug

# Release build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease

# Clean build
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew clean assembleDebug
```

### Installing on Device

```bash
# Install debug APK (device must be connected via adb)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use gradle
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installDebug
```

### Running Tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test
```

## Architecture Overview

### Layer Structure

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │ ChatScreen  │ │Conversations│ │    Settings Screens     ││
│  │ + ViewModel │ │Screen + VM  │ │                         ││
│  └─────────────┘ └─────────────┘ └─────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Services Layer                          │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ MessageSending   │ │ SocketEventHandler               │ │
│  │ Service          │ │ ├─ MessageEventHandler           │ │
│  ├──────────────────┤ │ ├─ ChatEventHandler              │ │
│  │ IncomingMessage  │ │ └─ SystemEventHandler            │ │
│  │ Handler          │ └──────────────────────────────────┘ │
│  ├──────────────────┤ ┌──────────────────────────────────┐ │
│  │ ChatFallback     │ │ Other Services:                  │ │
│  │ Tracker          │ │ - NotificationService            │ │
│  └──────────────────┘ │ - SyncService, SmsSendService    │ │
│                       └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Data Layer                              │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ Repositories     │ │ Local Storage                    │ │
│  │ - Message        │ │ - Room Database (DAOs, Entities) │ │
│  │ - Chat           │ │ - DataStore Preferences          │ │
│  │ - Attachment     │ │                                  │ │
│  └──────────────────┘ └──────────────────────────────────┘ │
│  ┌──────────────────┐ ┌──────────────────────────────────┐ │
│  │ Remote API       │ │ SocketService                    │ │
│  │ (Retrofit)       │ │ (Socket.IO)                      │ │
│  └──────────────────┘ └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Key Architecture Patterns

#### 1. Repository Pattern

Repositories abstract data sources and provide clean APIs to the UI layer:

- `MessageRepository` - Message CRUD and sync operations
- `ChatRepository` - Chat/conversation management
- `AttachmentRepository` - Attachment download/upload

#### 2. Service Layer Delegation

Complex services are decomposed into focused handlers:

- `SocketEventHandler` delegates to `MessageEventHandler`, `ChatEventHandler`, `SystemEventHandler`
- `MessageSendingService` handles all send operations (extracted from MessageRepository)
- `IncomingMessageHandler` handles incoming message processing

#### 3. ViewModel Delegates (Extensively Used)

Large ViewModels are decomposed into focused delegates. Each delegate:
- Is `@Inject` constructor-injected
- Has an `initialize(context, scope)` method called from ViewModel init
- Exposes state via `StateFlow` properties
- Contains related business logic methods

**ChatViewModel delegates** (`ui/chat/delegates/`):
- `ChatSendDelegate` - Send, retry, forward operations (uses `PendingAttachmentInput`)
- `ChatSearchDelegate` - In-chat message search
- `ChatOperationsDelegate` - Archive, star, delete, spam, block contact
- `ChatEffectsDelegate` - Message effect playback
- `ChatThreadDelegate` - Thread overlay
- `ChatSyncDelegate` - Adaptive polling and resume sync
- `ChatSendModeManager` - iMessage/SMS mode switching

**ConversationsViewModel delegates** (`ui/conversations/delegates/`):
- `ConversationLoadingDelegate` - Data loading and pagination
- `ConversationActionsDelegate` - Pin, mute, snooze, archive, delete
- `ConversationObserverDelegate` - Database/socket change observers (uses array-form combine for 12 flows)
- `UnifiedGroupMappingDelegate` - iMessage/SMS conversation merging

**ChatCreatorViewModel delegates** (`ui/chatcreator/delegates/`):
- `ContactLoadDelegate` - Contact loading and organization
- `ContactSearchDelegate` - Search and address validation
- `RecipientSelectionDelegate` - Selected recipients state
- `ChatCreationDelegate` - Chat creation operations

**SetupViewModel delegates** (`ui/setup/delegates/`):
- `PermissionsDelegate`, `ServerConnectionDelegate`, `SmsSetupDelegate`, etc.

#### 4. Service Interfaces (Testability)

Key services implement interfaces for dependency inversion and testability:

- `MessageSender` ← `MessageSendingService` - Message sending operations
- `SocketConnection` ← `SocketService` - Socket.IO connection management
- `IncomingMessageProcessor` ← `IncomingMessageHandler` - Incoming message handling
- `Notifier` ← `NotificationService` - Notification operations

Bindings are in `di/ServiceModule.kt`. Test fakes available in `src/test/kotlin/com/bothbubbles/fakes/`.

#### 5. Error Handling (AppError Framework)

Consistent error handling using sealed classes in `util/error/`:

- `AppError` - Base sealed class (abstract)
- `NetworkError` - Sealed class extending AppError (NoConnection, Timeout, ServerError, Unauthorized, Unknown)
- `DatabaseError` - Sealed class extending AppError (QueryFailed, InsertFailed, MigrationFailed)
- `MessageError` - Sealed class extending AppError (SendFailed, DeliveryFailed, AttachmentTooLarge)
- `SmsError` - Sealed class extending AppError (NoDefaultApp, PermissionDenied, CarrierBlocked)
- `ValidationError` - Sealed class extending AppError (InvalidInput, MissingRequired)

**Important**: These are sibling sealed classes, not nested. Use `ValidationError.InvalidInput(...)`, not `AppError.ValidationError(...)`.

- `safeCall {}` - Wrapper for operations that can fail
- `Result<T>.handle()` - Extension for handling success/failure

**RULE**: Repository methods that can fail MUST return `Result<T>`:
```kotlin
// REQUIRED for database mutations
suspend fun deleteMessage(guid: String): Result<Unit> = runCatching {
    dao.deleteMessage(guid)
}

// REQUIRED for network operations
suspend fun getServerCount(): Result<Int> = runCatching {
    val response = api.getCount()
    if (response.isSuccessful) {
        response.body()?.data ?: throw Exception("No data")
    } else {
        throw Exception("Request failed: ${response.code()}")
    }
}

// For early returns, use return@runCatching
suspend fun syncData(items: List<Item>): Result<Unit> = runCatching {
    if (items.isEmpty()) return@runCatching  // Early exit
    items.forEach { dao.insert(it) }
}
```

Methods that **require** `Result<T>`:
- Database insert/update/delete operations
- Network requests (API calls)
- File I/O operations
- Any operation that can throw exceptions

Methods that **don't need** `Result<T>`:
- Pure queries returning `Flow<T>` (errors handled by collector)
- Simple property accessors
- Pure in-memory calculations

#### 6. Attachment Types

Use `PendingAttachmentInput` for attachments throughout the send flow:

```kotlin
data class PendingAttachmentInput(
    val uri: Uri,
    val caption: String? = null,
    val mimeType: String? = null,
    val name: String? = null,
    val size: Long? = null
)
```

This type is used in `ChatViewModel._pendingAttachments`, `ChatSendDelegate.sendMessage()`, and `PendingMessageRepository.queueMessage()`.

#### 7. Contact/VCard Models

Contact-related models are defined in `services/contacts/VCardModels.kt`:

```kotlin
data class ContactData(
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    // ... phone numbers, emails, addresses, photo
)

data class FieldOptions(
    val includePhones: Boolean = true,
    val includeEmails: Boolean = true,
    // ... field selection for vCard export
)
```

These are top-level classes, not nested in `VCardService`. Import directly:
```kotlin
import com.bothbubbles.services.contacts.ContactData
import com.bothbubbles.services.contacts.FieldOptions
```

#### 8. Seam Architecture (Modular Messaging Platform)

Seam is the modular architecture that enables BothBubbles to support multiple messaging platforms. It consists of two key abstractions:

- **Stitches** - Platform integrations (SMS, BlueBubbles/iMessage)
- **Features (Hems)** - Cross-platform enhancements (Reels feed)

**Key Principles**:
- At least one Stitch must be enabled at all times
- Stitches wrap existing services using the wrapper pattern (don't replace)
- Features work across ALL Stitches when possible
- Dependency injection via Dagger Multibindings (`@IntoSet`)

**For complete documentation**, see `app/src/main/kotlin/com/bothbubbles/seam/README.md`.

**When creating new Stitches or Features**, use the templates:
- `seam/stitches/_template/README.md` - Stitch implementation guide
- `seam/hems/_template/README.md` - Feature implementation guide

## Project Structure

```
app/src/main/kotlin/com/bothbubbles/
├── data/                           # Data layer (Clean Architecture)
│   ├── local/
│   │   ├── db/
│   │   │   ├── dao/               # Room Data Access Objects
│   │   │   └── entity/            # Database entities
│   │   └── prefs/                 # DataStore preferences
│   ├── remote/
│   │   └── api/                   # Retrofit API interfaces and DTOs
│   └── repository/                # Repository implementations
│
├── di/                            # Hilt dependency injection modules
│   ├── AppModule.kt              # Application utilities (WorkManager, ImageLoader)
│   ├── DatabaseModule.kt         # Room database and all DAOs
│   ├── NetworkModule.kt          # Retrofit, OkHttp, Moshi, BothBubblesApi
│   ├── CoroutinesModule.kt       # Dispatchers and @ApplicationScope
│   ├── ServiceModule.kt          # Service interface bindings (testability)
│   ├── StitchModule.kt           # Stitch bindings (@IntoSet multibindings)
│   ├── FeatureModule.kt          # Feature bindings (@IntoSet multibindings)
│   ├── SmsModule.kt              # SMS/MMS dependencies (mostly auto-wired)
│   └── FcmModule.kt              # FCM/Firebase dependencies
│
├── seam/                          # Modular messaging platform architecture
│   ├── stitches/                 # Platform integrations
│   │   ├── Stitch.kt             # Core interface
│   │   ├── StitchConnectionState.kt  # Connection states
│   │   ├── StitchCapabilities.kt     # Capability declarations
│   │   ├── StitchRegistry.kt         # Collects all Stitches via DI
│   │   ├── StitchRouter.kt           # Routes operations to Stitches
│   │   ├── _template/                # Template for new Stitches
│   │   ├── sms/                      # SMS/MMS Stitch
│   │   └── bluebubbles/              # BlueBubbles/iMessage Stitch
│   └── hems/                     # Cross-platform features (called "Feature" in code)
│       ├── Feature.kt            # Core interface
│       ├── FeatureRegistry.kt    # Collects all Features via DI
│       ├── _template/            # Template for new Features
│       └── reels/                # Reels feed Feature
│
├── services/                      # Services layer
│   ├── foreground/               # Foreground services (SocketForegroundService)
│   ├── receiver/                 # Broadcast receivers (BootReceiver)
│   ├── messaging/                # Message sending and handling
│   │   ├── MessageSender.kt              # Interface for send operations
│   │   ├── MessageSendingService.kt      # Implementation of MessageSender
│   │   ├── IncomingMessageProcessor.kt   # Interface for incoming handling
│   │   ├── IncomingMessageHandler.kt     # Implementation
│   │   ├── ChatFallbackTracker.kt        # iMessage <-> SMS fallback
│   │   └── MessageSendWorker.kt          # WorkManager job
│   ├── notifications/            # Notification handling
│   │   ├── Notifier.kt                   # Interface
│   │   └── NotificationService.kt        # Implementation
│   ├── socket/                   # Socket.IO connection and events
│   │   ├── SocketConnection.kt           # Interface for socket operations
│   │   ├── SocketService.kt              # Implementation of SocketConnection
│   │   ├── SocketEventHandler.kt         # Event routing
│   │   └── handlers/                     # Decomposed event handlers
│   │       ├── MessageEventHandler.kt
│   │       ├── ChatEventHandler.kt
│   │       └── SystemEventHandler.kt
│   ├── sms/                      # SMS/MMS integration
│   └── sync/                     # Data synchronization
│
├── ui/                           # Presentation layer
│   ├── chat/                     # Chat screen
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── components/           # Chat-specific components
│   │   ├── delegates/            # ViewModel delegates (pattern)
│   │   └── paging/               # Message pagination
│   ├── conversations/            # Conversation list
│   ├── components/               # Shared UI components (organized)
│   │   ├── common/               # Avatar, LinkPreview, Shimmer, ErrorView
│   │   ├── message/              # MessageBubble, ReactionChip, TypingIndicator
│   │   ├── attachment/           # AttachmentPreview, ImageViewer, VideoPlayer
│   │   ├── conversation/         # ConversationTile, SwipeableConversationTile
│   │   ├── dialogs/              # ConfirmationDialog, InfoDialog, BottomSheet
│   │   └── input/                # SearchBar, TextInput, AttachmentPicker
│   ├── navigation/               # Type-safe navigation
│   ├── settings/                 # Settings screens (organized by feature)
│   │   ├── server/
│   │   ├── notifications/
│   │   ├── sms/
│   │   └── ...
│   └── theme/                    # Material Design 3 theming
│
└── util/                         # Utility classes (STATELESS ONLY)
    └── parsing/                  # Parsing utilities (Date, URL, Phone)
```

## Key Technologies

- **UI**: Jetpack Compose with Material Design 3
- **Architecture**: MVVM with ViewModel + StateFlow
- **DI**: Hilt (Dagger)
- **Database**: Room with reactive Flows
- **Network**: Retrofit + OkHttp + Moshi
- **Navigation**: Compose Navigation with type-safe routes (kotlinx.serialization)
- **Real-time**: Socket.IO for server communication
- **Background**: WorkManager for offline-first message delivery

## Coding Rules

### General Rules

- Use Kotlin idioms and best practices
- Follow Material Design 3 guidelines for UI
- Use StateFlow for UI state management
- Prefer Flow over LiveData
- Use Result types for operations that can fail

### Architecture Rules (MANDATORY)

- **RULE**: Data layer MUST NOT depend on Services layer (except through abstraction interfaces)
- **RULE**: Services layer CAN depend on Data layer
- **RULE**: UI layer CAN depend on both Data and Services layers
- **RULE**: ViewModels with >300 lines MUST use delegates
- **RULE**: Utilities MUST be stateless (no @Inject, no mutable state, no init())

### Anti-Pattern Validation (MANDATORY)

**RULE**: Before writing or modifying ANY code, you MUST validate that your approach is NOT an anti-pattern.

Before implementing:
1. **Consult anti-pattern docs** - Read relevant files in `docs/antipatterns/`
2. **Verify against known anti-patterns** - Check that your solution doesn't match common anti-patterns for the technology (Compose, Kotlin, Android, Room, Hilt, etc.)
3. **Consult layer READMEs** - Each layer's README documents anti-patterns specific to that layer. Read them.
4. **Question your approach** - If the solution feels complex, repetitive, or requires workarounds, it's likely an anti-pattern. Stop and reconsider.

Common anti-patterns to ALWAYS check for:
- **God objects** - Classes doing too much (use delegates/handlers)
- **Prop drilling** - Passing state through many layers (use CompositionLocal or push state collection down)
- **Collecting state too high** - Parent collecting state just to pass it down (violates leaf-node state rule)
- **Mutable collections in state** - Using `List` instead of `ImmutableList` in UI state
- **Lambda capturing** - Using `{ viewModel.method() }` instead of `viewModel::method`
- **Logic in composition** - Putting I/O, logging, or calculations in composable functions
- **Unsafe casts** - Using `as` instead of `as?` with safe defaults
- **Circular dependencies** - Layers depending on each other incorrectly
- **Leaking internals** - Exposing implementation details in public APIs
- **Force unwraps** - Using `!!` on nullable collection operations
- **SimpleDateFormat** - Not thread-safe, use DateTimeFormatter
- **runBlocking** - Blocks threads, defeats coroutines
- **Silent catches** - `catch (_: Exception) {}` hides bugs
- **Sensitive data in logs** - Auth keys, phone numbers, passwords

If you are unsure whether something is an anti-pattern, **ask before implementing**.

### File Organization Rules

- One primary class per file
- Keep files under 500 lines when possible
- Extract related functionality into separate handlers/delegates
- Use subdirectories for related files

## Message Flow

### Sending a Message

```
User Input → ChatViewModel → PendingMessageRepository
  → WorkManager (MessageSendWorker)
  → MessageSendingService
  → Either:
      - BothBubblesApi (iMessage via server)
      - SmsSendService/MmsSendService (local SMS/MMS)
```

### Receiving a Message

```
SocketEvent.NewMessage → SocketEventHandler
  → MessageEventHandler.handleNewMessage()
  → IncomingMessageHandler.handleIncomingMessage()
  → MessageDao.insertMessage()
  → NotificationService (if not active chat)
```

## Message Delivery Modes

1. **IMESSAGE** - Via BlueBubbles server (iMessage or server-routed SMS)
2. **LOCAL_SMS** - Direct SMS from Android device
3. **LOCAL_MMS** - Direct MMS from Android device
4. **AUTO** - Auto-select based on chat type and server connection

## Message Sync Mechanisms

The app uses multiple layers to ensure messages are never missed, as BlueBubbles server push can be unreliable:

### 1. Primary: Socket.IO Push

- Real-time message delivery via persistent socket connection
- Handled by `SocketEventHandler` → `MessageEventHandler`

### 2. Secondary: FCM Push

- Firebase Cloud Messaging as backup when socket disconnects
- Handled by `FcmMessageHandler`
- Triggers socket reconnect after showing notification

### 3. Fallback: Adaptive Polling (ChatViewModel)

- Polls every 2 seconds when socket has been quiet for >5 seconds
- Only active while viewing a chat
- Catches messages missed by push mechanisms
- Key constants: `POLL_INTERVAL_MS = 2000L`, `SOCKET_QUIET_THRESHOLD_MS = 5000L`

### 4. Fallback: Foreground Resume Sync (ChatViewModel)

- Syncs when app returns from background
- Fetches up to 25 recent messages for active chat
- Uses `AppLifecycleTracker.foregroundState` StateFlow

### 5. Fallback: Background Sync Worker

- `BackgroundSyncWorker` runs every 15 minutes (Android minimum)
- Syncs up to 10 recent chats, 20 messages each
- Shows notifications if app is backgrounded and new messages found
- Scheduled in `BothBubblesApp.initializeBackgroundSync()`
- Respects chat notification settings (muted/snoozed)

### Key Files

- `services/sync/BackgroundSyncWorker.kt` - Background periodic sync
- `services/AppLifecycleTracker.kt` - App foreground/background state
- `ui/chat/ChatViewModel.kt` - Adaptive polling and foreground resume sync

## Workflow

- **Do not automatically build** after making code changes. Only build when explicitly requested by the user.
- Run lint/tests when making significant changes
- Prefer editing existing files over creating new ones

### Troubleshooting Rules (MANDATORY)

**RULE**: NEVER clear logs (logcat, ACRA reports, crash buffers, or any diagnostic data) without explicit user approval. Logs are critical for diagnosing issues and clearing them prematurely destroys valuable debugging information. Always ask the user before running commands like:
- `adb logcat -c` (clears logcat buffer)
- Deleting ACRA crash report files
- Any command that removes or truncates log files

## Debugging Crashes via ADB

When the app crashes, use the following process to retrieve the crash report:

### 1. Check for ACRA Crash Reports (Preferred)

The app uses ACRA (Application Crash Report for Android) with a custom crash handler. Crash reports are stored in the app's private directory:

```bash
# Find crash report files
adb shell "run-as com.bothbubbles.messaging find /data/data/com.bothbubbles.messaging -name '*crash*' -o -name '*ACRA*' -o -name '*stacktrace*'"

# Read the most recent crash stacktrace (unapproved = not yet sent)
adb shell "run-as com.bothbubbles.messaging cat '/data/data/com.bothbubbles.messaging/app_ACRA-unapproved/<timestamp>.stacktrace'"
```

The stacktrace file is JSON containing:
- `STACK_TRACE`: Full exception stack trace
- `LOGCAT`: Recent logcat entries before the crash
- Device info, configuration, and more

### 2. Alternative: Check Logcat for FATAL EXCEPTION

If ACRA didn't catch the crash:

```bash
# Get recent crash buffer
adb logcat -d -b crash | tail -150

# Search for fatal exceptions
adb logcat -d | grep -B 5 -A 100 "FATAL EXCEPTION" | tail -150

# Filter by app package
adb logcat -d | grep -E "AndroidRuntime|Exception|Error" | grep -i bothbubbles | tail -50
```

### 3. Check App-Specific Logs

```bash
# Get logs from the app's process around crash time
adb logcat -d -t "12-24 20:04:20.000" | grep -E "<PID>|bothbubbles" | head -100
```

### Common Crash Patterns

1. **IndexOutOfBoundsException in LazyColumn** - Usually indicates race condition where list data changes during scroll. Check for concurrent updates to list state.

2. **ClassCastException in Flow combine** - Using `as` instead of `as?` for StateFlow values. Always use safe casts with defaults.

3. **NullPointerException from `!!`** - Force unwrap on nullable collection operations. Use safe alternatives like `firstOrNull()`, `minOrNull()`.

## Common Tasks

### Adding a New Settings Screen

1. Create `ui/settings/{feature}/{Feature}Screen.kt`
2. Add route to `Screen.kt`
3. Add composable to `NavHost.kt`
4. Add navigation from `SettingsScreen.kt`

### Adding a Socket Event Handler

1. Add event type to `SocketEvent.kt`
2. Add parsing in `SocketService.kt`
3. Add handler method in appropriate handler (`MessageEventHandler`, `ChatEventHandler`, or `SystemEventHandler`)
4. Wire up in `SocketEventHandler.handleEvent()`

### Extracting from Large ViewModels

1. Create delegate class in `ui/{feature}/delegates/`
2. Add dependencies via constructor injection
3. Add `initialize(context, scope)` method
4. Move related state and methods
5. See `ChatSendDelegate.kt` for example

## UI Guidelines

### Material Design 3

- Use `androidx.compose.material3` components for all new UI.
- Use `MaterialTheme.colorScheme` for colors (e.g., `primary`, `surfaceVariant`).
- Use `MaterialTheme.typography` for text styles.

### System Integration

- **Keyboard**: Use `WindowInsets.ime` and `Modifier.imePadding()` for keyboard handling.
- **Haptics**: Use `LocalHapticFeedback.current` for tactile feedback.
- **Media**: Prefer `ActivityResultContracts.PickVisualMedia` (Photo Picker) over custom galleries.
- **Icons**: Use `androidx.compose.material.icons` whenever possible.

### Common Compose Import Patterns

**Icons** - Use `Icons.Default.*` (capital D) or explicit `Icons.Filled.*`:
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search  // Explicit
// Then use: Icons.Default.Search or Icons.Filled.Search
```

**Layout modifiers** - Many modifiers are in specific packages:
```kotlin
import androidx.compose.ui.layout.onSizeChanged      // NOT foundation.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
```

**Animation** - AnimatedContent and transitions:
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
```

### Flow Combine for Many Flows

When combining 6+ flows, use array syntax with **safe casts and defaults**:
```kotlin
combine(
    flow1, flow2, flow3, flow4, flow5, flow6, flow7, flow8
) { values: Array<Any?> ->
    @Suppress("UNCHECKED_CAST")
    val value1 = values[0] as? Boolean ?: false        // Safe cast with default
    val value2 = values[1] as? String                   // Nullable stays nullable
    val value3 = values[2] as? Float ?: 0f              // Safe cast with default
    // ... extract each value with safe type cast
}
```

**Important**: Use `as?` (safe cast) with Elvis operator defaults, not `as` (unsafe cast). StateFlow values can be null during initialization, causing `ClassCastException` at runtime.

The standard `combine` with trailing lambda only supports up to 5 flows.

## Settings Panel Architecture

The `SettingsPanel` uses internal navigation with `AnimatedContent` for smooth transitions between settings pages. Each settings page has a `*Content` composable that can be embedded in the panel:

- `ServerSettingsContent()` - Uses hiltViewModel with defaults
- `ArchivedChatsContent(onChatClick)` - Requires callback
- `ExportPanelContent()` - Wrapper with dialogs (see SettingsPanel.kt)

When adding new settings to the panel:
1. Create `{Feature}Content` composable with `viewModel = hiltViewModel()` default
2. Add to `SettingsPanelPage` enum
3. Add case in `SettingsPanel` AnimatedContent switch
4. Add import to SettingsPanel.kt
