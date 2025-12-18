# BothBubbles vs BlueBubbles: Networking & FCM Registration Comparison Report

**Date:** 2024-12-17
**Reference:** `references/bluebubbles-old/`

---

## Executive Summary

This report compares the networking and FCM registration flows between BothBubbles (native Kotlin) and the reference BlueBubbles app (Flutter). The analysis identifies **7 missing features** and **2 potential issues** in BothBubbles that could affect reliability and feature parity.

### Severity Ratings
- **CRITICAL**: Missing functionality that breaks core features
- **HIGH**: Missing functionality that affects reliability
- **MEDIUM**: Missing features that affect user experience
- **LOW**: Nice-to-have features

---

## 1. Server Connection Flow

### What Reference BlueBubbles Does

| Step | Implementation | File |
|------|---------------|------|
| 1. Store credentials | `guidAuthKey`, `serverAddress`, `customHeaders` in SharedPreferences | `settings.dart` |
| 2. Build API URL | `{origin}/api/v1` with `guid` query param | `http_service.dart` |
| 3. Add tunnel headers | Auto-add `ngrok-skip-browser-warning`, `skip_zrok_interstitial` | `http_service.dart:23-27` |
| 4. Localhost detection | Scan local network for BlueBubbles server port | `network_tasks.dart` |
| 5. Origin override | Store local IP when on same network for faster access | `http_service.dart` (via `originOverride`) |

### What BothBubbles Does

| Step | Implementation | File |
|------|---------------|------|
| 1. Store credentials | `server_address`, `guid_auth_key`, `custom_headers` in DataStore | `ServerPreferences.kt` |
| 2. Build API URL | Dynamic URL replacement with `guid` query param | `AuthInterceptor.kt` |
| 3. Add tunnel headers | Auto-add ngrok/zrok headers based on host detection | `AuthInterceptor.kt:92-97` |
| 4. Localhost detection | **NOT IMPLEMENTED** | - |
| 5. Origin override | **NOT IMPLEMENTED** | - |

### Gap Analysis

| Feature | Status | Severity | Notes |
|---------|--------|----------|-------|
| Credential storage | ✅ Implemented | - | Uses DataStore instead of SharedPreferences |
| API authentication | ✅ Implemented | - | Same `guid` query parameter pattern |
| Tunnel service headers | ✅ Implemented | - | ngrok + zrok support |
| Localhost detection | ❌ Missing | **MEDIUM** | Could improve performance on local network |
| Origin override | ❌ Missing | **MEDIUM** | Related to localhost detection |

---

## 2. FCM Registration Flow

### What Reference BlueBubbles Does

| Step | Implementation | File |
|------|---------------|------|
| 1. Check prerequisites | `finishedSetup` + not `keepAppAlive` | `cloud_messaging_service.dart` |
| 2. Fetch FCM config | `GET /api/v1/fcm/client` | `http_service.dart` |
| 3. Parse FCM data | Extract `projectID`, `storageBucket`, `apiKey`, `firebaseURL`, `clientID`, `applicationID` | `fcm_data.dart` |
| 4. Initialize Firebase | Dynamic `FirebaseApp.initializeApp()` with server config | `FirebaseAuthHandler.kt` |
| 5. Get FCM token | `FirebaseMessaging.getInstance().token` | `FirebaseCloudMessagingTokenHandler.kt` |
| 6. Register with server | `POST /api/v1/fcm/device` with `{name, identifier}` | `http_service.dart` |
| 7. Store in SharedPrefs | Save all FCM config values for native access | `fcm_data.dart:47-77` |

### What BothBubbles Does

| Step | Implementation | File |
|------|---------------|------|
| 1. Check prerequisites | `isSetupComplete` flag | `FcmTokenManager.kt` |
| 2. Fetch FCM config | `GET /api/v1/fcm/client` | `BothBubblesApi.kt` |
| 3. Parse FCM data | Extract `projectNumber`, `projectId`, `appId`, `apiKey`, `storageBucket` | `FcmClientDto.kt` |
| 4. Initialize Firebase | Dynamic `FirebaseApp.initializeApp()` | `FirebaseConfigManager.kt` |
| 5. Get FCM token | `FirebaseMessaging.getInstance().token.await()` | `FcmTokenManager.kt` |
| 6. Register with server | `POST /api/v1/fcm/device` via WorkManager | `FcmTokenRegistrationWorker.kt` |
| 7. Store in DataStore | Save config for cache/offline | `NotificationPreferences.kt` |

### Gap Analysis

| Feature | Status | Severity | Notes |
|---------|--------|----------|-------|
| FCM config fetch | ✅ Implemented | - | Same endpoint |
| Firebase initialization | ✅ Implemented | - | Dynamic config |
| FCM token retrieval | ✅ Implemented | - | Uses coroutines |
| Device registration | ✅ Implemented | - | WorkManager with retry |
| `firebaseURL` storage | ❌ Missing | **HIGH** | Needed for Firebase Database server URL sync |
| `clientID` storage | ⚠️ Not needed | - | Only needed for OAuth on web/desktop |

### FCM Data Fields Comparison

| Field | Reference | BothBubbles | Used For |
|-------|-----------|-------------|----------|
| `projectID` / `projectId` | ✅ | ✅ | Firebase project identifier |
| `storageBucket` | ✅ | ✅ | Firebase Storage |
| `apiKey` | ✅ | ✅ | Firebase API key |
| `applicationID` / `appId` | ✅ | ✅ | Firebase App ID |
| `projectNumber` (gcmSenderId) | ❌ | ✅ | GCM sender ID |
| `firebaseURL` (databaseURL) | ✅ | ❌ | **Firebase Realtime Database** |
| `clientID` | ✅ | ❌ | OAuth (web/desktop only) |

---

## 3. Firebase Database for Dynamic Server URL

### What Reference BlueBubbles Does

This is a **CRITICAL** feature for users with dynamic DNS or changing server URLs.

```
┌─────────────────────────────────────────────────────────────┐
│           Firebase Database Server URL Sync                │
│                                                             │
│   BlueBubbles Server writes serverUrl to Firebase DB       │
│                    ↓                                        │
│   Firebase Realtime DB: config/serverUrl                   │
│   OR                                                        │
│   Firebase Firestore: server/config.serverUrl              │
│                    ↓                                        │
│   App listens for changes (ValueEventListener)             │
│                    ↓                                        │
│   NewServerUrl callback updates local settings             │
│                    ↓                                        │
│   Socket reconnects to new server URL                      │
└─────────────────────────────────────────────────────────────┘
```

**Key Files:**
- `FirebaseDatabaseListener.kt` - Realtime Database listener
- `FirestoreDatabaseListener.kt` - Firestore listener
- `firebase_database_service.dart` - Fetches URL on demand

**Implementation Details:**
```kotlin
// Reference: FirebaseDatabaseListener.kt:14-21
class RealtimeDatabaseListener: ValueEventListener {
    override fun onDataChange(snapshot: DataSnapshot) {
        val serverUrl: String? = snapshot.child("serverUrl").getValue(String::class.java)
        if (serverUrl != null) {
            MethodCallHandler.invokeMethod("NewServerUrl", mapOf("server_url" to serverUrl))
        }
    }
}
```

### What BothBubbles Does

**NOT IMPLEMENTED**

BothBubbles has no mechanism to:
1. Store `firebaseURL` (Firebase Realtime Database URL)
2. Listen to Firebase Database for server URL changes
3. Auto-update server URL when it changes

### Gap Analysis

| Feature | Status | Severity | Impact |
|---------|--------|----------|--------|
| `firebaseURL` storage | ❌ Missing | **CRITICAL** | Cannot connect to Firebase Database |
| Realtime Database listener | ❌ Missing | **CRITICAL** | No dynamic URL updates |
| Firestore listener | ❌ Missing | **CRITICAL** | No dynamic URL updates |
| `fetchNewUrl()` on-demand | ❌ Missing | **HIGH** | Cannot manually refresh URL |

### Recommended Implementation

```kotlin
// 1. Add to NotificationPreferences.kt
val firebaseDatabaseUrl: Flow<String> = dataStore.data.map { prefs ->
    prefs[Keys.FIREBASE_DATABASE_URL] ?: ""
}

// 2. Create FirebaseDatabaseService.kt
@Singleton
class FirebaseDatabaseService @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private var database: FirebaseDatabase? = null
    private var urlListener: ValueEventListener? = null

    suspend fun startListening() {
        val databaseUrl = settingsDataStore.firebaseDatabaseUrl.first()
        if (databaseUrl.isBlank()) return

        val db = FirebaseDatabase.getInstance(databaseUrl)
        val ref = db.getReference("config/serverUrl")

        urlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newUrl = snapshot.getValue(String::class.java) ?: return
                applicationScope.launch {
                    settingsDataStore.setServerAddress(newUrl)
                    // Trigger socket reconnect
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(urlListener!!)
    }
}
```

---

## 4. Socket.IO Connection

### What Reference BlueBubbles Does

| Feature | Implementation | File |
|---------|---------------|------|
| Authentication | `guid` query parameter | `socket_service.dart:59` |
| Transports | WebSocket + polling fallback | `socket_service.dart:60` |
| Custom headers | From `http.headers` | `socket_service.dart:61` |
| Auto-reconnect | Built-in with exponential backoff | `socket_service.dart:64` |
| Encrypted responses | `decryptAESCryptoJS()` for encrypted payloads | `socket_service.dart:133-135` |

**Encrypted Message Handling:**
```dart
// Reference: socket_service.dart:130-136
Future<Map<String, dynamic>> sendMessage(String event, Map<String, dynamic> message) {
  // ...
  socket.emitWithAck(event, message, ack: (response) {
    if (response['encrypted'] == true) {
      response['data'] = jsonDecode(decryptAESCryptoJS(response['data'], password));
    }
    // ...
  });
}
```

### What BothBubbles Does

| Feature | Implementation | File |
|---------|---------------|------|
| Authentication | `guid` query parameter (URL-encoded) | `SocketIOConnection.kt` |
| Transports | WebSocket + polling fallback | `SocketIOConnection.kt` |
| Custom headers | Not implemented for Socket.IO | - |
| Auto-reconnect | Built-in (5s-60s backoff) | `SocketIOConnection.kt` |
| Encrypted responses | **NOT IMPLEMENTED** | - |

### Gap Analysis

| Feature | Status | Severity | Notes |
|---------|--------|----------|-------|
| Basic authentication | ✅ Implemented | - | Same pattern |
| Transport fallback | ✅ Implemented | - | WebSocket + polling |
| Custom headers | ⚠️ Partial | **LOW** | HTTP has it, Socket.IO may not |
| Encrypted responses | ❌ Missing | **MEDIUM** | Server may send encrypted payloads |

### Socket Events Comparison

| Event | Reference | BothBubbles | Notes |
|-------|-----------|-------------|-------|
| `new-message` | ✅ | ✅ | |
| `updated-message` | ✅ | ✅ | |
| `message-deleted` | ✅ | ✅ | |
| `typing-indicator` | ✅ | ✅ | |
| `chat-read-status-changed` | ✅ | ✅ | |
| `participant-added` | ✅ | ✅ | |
| `participant-removed` | ✅ | ✅ | |
| `participant-left` | ✅ | ✅ | |
| `group-name-change` | ✅ | ✅ | |
| `group-icon-changed` | ✅ | ✅ | |
| `group-icon-removed` | ✅ | ✅ | |
| `ft-call-status-changed` | ✅ | ✅ | |
| `incoming-facetime` | ✅ | ✅ | |
| `server-update` | ✅ | ✅ | |
| `imessage-aliases-removed` | ✅ | ❌ | **Missing event** |
| `scheduled-message-*` | ✅ | ✅ | |
| `message-send-error` | ✅ | ✅ | |
| `icloud-account` | ✅ | ✅ | |

---

## 5. FCM Message Handling

### What Reference BlueBubbles Does

```kotlin
// Reference: BlueBubblesFirebaseMessagingService.kt
override fun onMessageReceived(message: RemoteMessage) {
    val type = message.data["type"] ?: return
    DartWorkManager.createWorker(applicationContext, type, HashMap(message.data)) {}

    // Optional Tasker integration
    if (prefs.getBoolean("flutter.sendEventsToTasker", false)) {
        // Send broadcast to Tasker
    }
}
```

The reference app uses `DartWorkManager` to:
1. Create a background worker for each FCM message type
2. Execute Dart code to process the message
3. Update the UI via method channels

### What BothBubbles Does

```kotlin
// BothBubblesFirebaseService.kt
override fun onMessageReceived(message: RemoteMessage) {
    applicationScope.launch(Dispatchers.IO) {
        withTimeoutOrNull(10_000L) {
            fcmMessageHandler.handleMessage(message)
        }
    }
}
```

BothBubbles processes FCM messages directly in the Firebase service with:
1. 10-second timeout for processing
2. Direct handler invocation (no WorkManager)
3. Socket reconnection trigger after processing

### Gap Analysis

| Feature | Status | Severity | Notes |
|---------|--------|----------|-------|
| Message processing | ✅ Implemented | - | Direct processing |
| Socket reconnect trigger | ✅ Implemented | - | On FCM message receipt |
| Timeout protection | ✅ Implemented | - | 10-second limit |
| WorkManager delegation | ⚠️ Different | **LOW** | Direct processing may be faster |
| Tasker integration | ❌ Missing | **LOW** | Power user feature |

---

## 6. UnifiedPush Support

### What Reference BlueBubbles Does

UnifiedPush provides an alternative to FCM for users who want:
- Privacy from Google
- Support on degoogled devices
- Self-hosted push notification delivery

```kotlin
// Reference: UnifiedPushReceiver.kt
class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        DartWorkManager.createWorker(context, "unifiedpush-settings", data) {}
    }

    override fun onMessage(context: Context, payload: ByteArray, instance: String) {
        val type = json.get("type")?.getAsString() ?: return
        DartWorkManager.createWorker(applicationContext, type, HashMap(json)) {}
    }
}
```

### What BothBubbles Does

**NOT IMPLEMENTED**

### Gap Analysis

| Feature | Status | Severity | Notes |
|---------|--------|----------|-------|
| UnifiedPush receiver | ❌ Missing | **MEDIUM** | Important for degoogled devices |
| Endpoint registration | ❌ Missing | **MEDIUM** | |
| Message handling | ❌ Missing | **MEDIUM** | |

---

## 7. Android Foreground Service Socket.IO

### What Reference BlueBubbles Does

```kotlin
// Reference: SocketIOForegroundService.kt:125
opts.query = "password=$encodedPw"
```

**NOTE:** The Android foreground service uses `password=` as the query parameter, while the Dart code uses `guid=`. The server likely accepts both.

### What BothBubbles Does

Uses `guid=` consistently across both HTTP and Socket.IO.

### Potential Issue

The reference app has an inconsistency where:
- Dart Socket.IO uses: `guid=password`
- Android Socket.IO uses: `password=encodedPw`

BothBubbles is **consistent** with using `guid=` everywhere, which should be correct based on the Dart implementation being the primary reference.

---

## 8. Summary of Missing Features

### Critical (Should Implement)

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 1 | Firebase Database URL storage | Cannot sync server URL dynamically | Low |
| 2 | Firebase Realtime Database listener | Users with dynamic DNS cannot auto-reconnect | Medium |
| 3 | Firebase Firestore listener (fallback) | Alternative to Realtime Database | Medium |

### High Priority

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 4 | `fetchNewUrl()` on-demand | Cannot manually refresh server URL | Low |
| 5 | Socket encrypted response handling | May fail on encrypted server responses | Medium |

### Medium Priority

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 6 | Localhost detection | Suboptimal performance on local network | Medium |
| 7 | UnifiedPush support | Cannot use on degoogled devices | High |
| 8 | `imessage-aliases-removed` event | Missing socket event | Low |

### Low Priority

| # | Feature | Impact | Effort |
|---|---------|--------|--------|
| 9 | Tasker integration | Power user automation | Medium |
| 10 | Socket.IO custom headers | May affect some tunnel services | Low |

---

## 9. Recommendations

### Immediate Actions (Critical)

1. **Add `firebaseURL`/`databaseURL` to FCM config storage**
   - File: `NotificationPreferences.kt`
   - Also update `FcmClientDto.kt` to parse `firebase_url` from `project_info`

2. **Implement Firebase Database listener service**
   - Create `FirebaseDatabaseService.kt`
   - Listen to `config/serverUrl` for URL changes
   - Auto-update server address and reconnect socket

### Short-Term (High Priority)

3. **Add encrypted socket response handling**
   - Check for `encrypted: true` in socket responses
   - Implement AES decryption using the password as key

4. **Add on-demand URL refresh**
   - Add a "Refresh Server URL" button in settings
   - Fetch from Firebase Database when tapped

### Medium-Term

5. **Implement localhost detection**
   - Call `/api/v1/server/info` to get server's local IPs
   - Ping local addresses when on WiFi
   - Store in `originOverride` for faster local connections

6. **Add UnifiedPush support**
   - Implement `UnifiedPushReceiver`
   - Add toggle in notification settings
   - Register endpoint with server

---

## 10. Code Locations for Reference

### BothBubbles (Current Implementation)

| Component | File |
|-----------|------|
| Auth Interceptor | [AuthInterceptor.kt](core/network/src/main/kotlin/com/bothbubbles/core/network/api/AuthInterceptor.kt) |
| FCM Config Manager | [FirebaseConfigManager.kt](app/src/main/kotlin/com/bothbubbles/services/fcm/FirebaseConfigManager.kt) |
| FCM Token Manager | [FcmTokenManager.kt](app/src/main/kotlin/com/bothbubbles/services/fcm/FcmTokenManager.kt) |
| FCM Registration Worker | [FcmTokenRegistrationWorker.kt](app/src/main/kotlin/com/bothbubbles/services/fcm/FcmTokenRegistrationWorker.kt) |
| FCM Message Handler | [FcmMessageHandler.kt](app/src/main/kotlin/com/bothbubbles/services/fcm/FcmMessageHandler.kt) |
| Socket.IO Connection | [SocketIOConnection.kt](app/src/main/kotlin/com/bothbubbles/services/socket/SocketIOConnection.kt) |
| Notification Preferences | [NotificationPreferences.kt](core/data/src/main/kotlin/com/bothbubbles/core/data/prefs/NotificationPreferences.kt) |

### Reference BlueBubbles

| Component | File |
|-----------|------|
| HTTP Service | `lib/services/network/http_service.dart` |
| Socket Service | `lib/services/network/socket_service.dart` |
| FCM Data Model | `lib/database/io/fcm_data.dart` |
| Firebase Auth Handler | `android/.../firebase/FirebaseAuthHandler.kt` |
| Firebase Database Listener | `android/.../firebase/FirebaseDatabaseListener.kt` |
| Firebase Messaging Service | `android/.../firebase/BlueBubblesFirebaseMessagingService.kt` |
| UnifiedPush Receiver | `android/.../UnifiedPushReceiver.kt` |

---

## Appendix A: FCM Config JSON Structure

Server returns Firebase config in `google-services.json` format:

```json
{
  "project_info": {
    "project_number": "123456789",
    "project_id": "my-project",
    "storage_bucket": "my-project.appspot.com",
    "firebase_url": "https://my-project.firebaseio.com"
  },
  "client": [{
    "client_info": {
      "mobilesdk_app_id": "1:123456789:android:abc123",
      "android_client_info": {
        "package_name": "com.bothbubbles.messaging"
      }
    },
    "api_key": [{
      "current_key": "AIzaSy..."
    }],
    "oauth_client": [{
      "client_id": "123456789-abc.apps.googleusercontent.com"
    }]
  }]
}
```

**Note:** `firebase_url` in `project_info` is missing from BothBubbles parsing.

---

## Appendix B: Firebase Database Paths

| Database Type | Path | Field |
|--------------|------|-------|
| Realtime Database | `/config` | `serverUrl` |
| Firestore | `server/config` | `serverUrl` |

The server writes its current URL to one of these locations. The app listens for changes and updates accordingly.

---

## Appendix C: Flow Diagrams

### Current BothBubbles Flow (Simplified)

```
┌─────────────────────────────────────────────────────────────┐
│                    App Startup                              │
│  1. Load saved server URL from DataStore                   │
│  2. Initialize Firebase from cached config                 │
│  3. Get/refresh FCM token                                  │
│  4. Register token with server (WorkManager)               │
│  5. Connect Socket.IO                                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Runtime                                  │
│  Socket.IO ◄──────────────────────────────────► Server     │
│      │                                              │       │
│      ▼                                              │       │
│  Events processed locally                           │       │
│                                                     │       │
│  FCM ◄───────────────────────────────────── Push ──┘       │
│      │                                                      │
│      ▼                                                      │
│  Show notification + reconnect socket                      │
└─────────────────────────────────────────────────────────────┘
```

### Reference BlueBubbles Flow (With Firebase DB)

```
┌─────────────────────────────────────────────────────────────┐
│                    App Startup                              │
│  1. Load saved server URL from SharedPrefs                 │
│  2. Initialize Firebase from cached config                 │
│  3. Start Firebase Database listener  ◄── MISSING          │
│  4. Get/refresh FCM token                                  │
│  5. Register token with server                             │
│  6. Connect Socket.IO                                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Runtime                                  │
│                                                             │
│  Firebase DB ◄──── Server writes new URL ────┐             │
│      │                                        │             │
│      ▼                                        │             │
│  Update local settings                        │             │
│      │                                        │             │
│      ▼                                        │             │
│  Reconnect Socket.IO to new URL              │             │
│                                               │             │
│  Socket.IO ◄─────────────────────────────────► Server     │
│      │                                              │       │
│      ▼                                              │       │
│  Events processed                                   │       │
│                                                     │       │
│  FCM ◄───────────────────────────────────── Push ──┘       │
│      │                                                      │
│      ▼                                                      │
│  DartWorkManager processes message                         │
└─────────────────────────────────────────────────────────────┘
```

---

*Report generated by Claude Code analysis*
