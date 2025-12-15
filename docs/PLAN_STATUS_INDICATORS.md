# Status Indicators: Spotify & Xbox Live Integration

## Overview

Add real-time activity status indicators showing what contacts are listening to (Spotify) or playing (Xbox Live). Status appears beneath contact names in chat headers and on contact detail screens.

## User Experience Goals

1. **Glanceable** - Quick visual indicator without disrupting conversation flow
2. **Non-intrusive** - Subtle presence that doesn't compete with messages
3. **Timely** - Shows current activity or recency ("2m ago", "1h ago")
4. **Opt-in** - User controls which contacts have linked accounts
5. **Privacy-aware** - Clear about what data is accessed and when

---

## UI Design

### Chat Header Status Display

```
┌─────────────────────────────────────────────┐
│  ←  John Smith                          ⋮   │
│      🎵 Bohemian Rhapsody • Queen           │  ← Spotify status
│         Now playing                          │
├─────────────────────────────────────────────┤
│                                             │
│  Messages...                                │
```

```
┌─────────────────────────────────────────────┐
│  ←  Mike Johnson                        ⋮   │
│      🎮 Rocket League                       │  ← Xbox status
│         Playing now                          │
├─────────────────────────────────────────────┤
```

```
┌─────────────────────────────────────────────┐
│  ←  Sarah Williams                      ⋮   │
│      🎵 Anti-Hero • Taylor Swift            │
│         32m ago                              │  ← Recent activity
├─────────────────────────────────────────────┤
```

### Visual Specifications

- **Icon**: Platform icon (🎵 for Spotify green, 🎮 for Xbox green)
- **Primary text**: Song name / Game name (single line, ellipsize)
- **Secondary text**: Artist name (Spotify only) / "Playing now" or time ago
- **Typography**: `labelMedium` for primary, `labelSmall` for secondary
- **Color**: `MaterialTheme.colorScheme.onSurfaceVariant` (muted)
- **Animation**: Subtle fade-in when status updates

### Contact Card / Profile Screen

```
┌─────────────────────────────────────────────┐
│                                             │
│              [  Avatar  ]                   │
│              John Smith                     │
│           +1 (555) 123-4567                 │
│                                             │
├─────────────────────────────────────────────┤
│  Activity                                   │
│  ┌─────────────────────────────────────┐   │
│  │ 🎵 Spotify                          │   │
│  │    Bohemian Rhapsody                │   │
│  │    Queen • A Night at the Opera     │   │
│  │    Now playing                       │   │
│  └─────────────────────────────────────┘   │
│  ┌─────────────────────────────────────┐   │
│  │ 🎮 Xbox Live                        │   │
│  │    Rocket League                     │   │
│  │    Online • In Match                 │   │
│  │    15m ago                           │   │
│  └─────────────────────────────────────┘   │
│                                             │
├─────────────────────────────────────────────┤
│  Linked Accounts                            │
│  ┌─────────────────────────────────────┐   │
│  │ + Link Spotify account              │   │
│  │ + Link Xbox Gamertag                │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### Status Priority (when multiple active)

1. Currently active takes priority over past activity
2. If both currently active: most recently started
3. If both past: most recent timestamp
4. User can configure preferred platform in settings

---

## Setup Flow

### Initial Authorization (User's Own Accounts)

The user must first authorize BothBubbles to access their own Spotify/Xbox accounts. This enables the "friend activity" APIs.

#### Settings Screen Addition

```
Settings
├── ...
├── Status Indicators                    →
│   ├── Spotify
│   │   ├── [Connect Spotify Account]     ← OAuth button
│   │   ├── Show friend activity          ← Toggle
│   │   └── Connected as: username        ← After auth
│   │
│   ├── Xbox Live
│   │   ├── [Sign in with Microsoft]      ← OAuth button
│   │   ├── Show friend activity          ← Toggle
│   │   └── Connected as: Gamertag        ← After auth
│   │
│   └── Display Options
│       ├── Show in chat headers          ← Toggle (default: on)
│       ├── Show on contact cards         ← Toggle (default: on)
│       └── Activity timeout: [1h ▼]      ← How old before hiding
```

### Spotify OAuth Flow

1. User taps "Connect Spotify Account"
2. Opens Spotify authorization URL in browser/Custom Tab
3. User logs in and grants permissions
4. Redirect back to app with auth code
5. Exchange code for access token + refresh token
6. Store tokens securely (EncryptedSharedPreferences)

**Required Scopes:**
- `user-read-currently-playing` - See what user is playing
- `user-read-recently-played` - See recent tracks
- `user-read-playback-state` - See playback state (paused, etc.)

**Note:** Spotify does NOT provide friend activity via API. We need the contact to share their own status. See "Linking Contacts" below.

### Xbox Live OAuth Flow

1. User taps "Sign in with Microsoft"
2. Opens Microsoft identity platform authorization
3. User logs in with Microsoft account linked to Xbox
4. Redirect back with auth code
5. Exchange for tokens
6. Store securely

**Required Scopes:**
- `XboxLive.signin` - Basic Xbox Live sign-in
- `XboxLive.offline_access` - Refresh tokens

**Xbox Live Friend Activity:** Xbox Live API provides friend presence data for friends on your friends list.

---

## Linking Contacts to Accounts

### Challenge

Neither Spotify nor Xbox provides a way to look up users by phone number or email. We need to manually link contacts to their platform usernames.

### Solution: Manual Linking with Search

#### Link Flow (from Contact Card)

```
┌─────────────────────────────────────────────┐
│  Link Spotify Account                   ✕   │
├─────────────────────────────────────────────┤
│                                             │
│  Search for John's Spotify username:        │
│  ┌─────────────────────────────────────┐   │
│  │ 🔍 Search username or paste link    │   │
│  └─────────────────────────────────────┘   │
│                                             │
│  Or ask John to share their profile:        │
│  [Send Request Message]                     │
│                                             │
│  Recent searches:                           │
│  • johnsmith123                             │
│  • john.smith.music                         │
│                                             │
├─────────────────────────────────────────────┤
│  Search Results:                            │
│  ┌─────────────────────────────────────┐   │
│  │ [avatar] johnsmith123               │   │
│  │          John S. • 42 playlists    [Link]│
│  └─────────────────────────────────────┘   │
│  ┌─────────────────────────────────────┐   │
│  │ [avatar] john_smith_official        │   │
│  │          John Smith • 3 playlists  [Link]│
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

#### Link via Shared Profile URL

User can paste a profile URL directly:
- Spotify: `https://open.spotify.com/user/username`
- Xbox: `https://www.xbox.com/play/user/gamertag`

App parses the username from the URL.

#### "Request Link" Message (Optional Enhancement)

Send a pre-formatted message asking the contact for their username:

```
"Hey! I'm using BothBubbles and can show your Spotify/Xbox activity
in our chat. Want to share your username?

Spotify: open.spotify.com/user/YOUR_USERNAME
Xbox: Your Gamertag"
```

### Data Model for Linked Accounts

```kotlin
@Entity(tableName = "contact_linked_accounts")
data class ContactLinkedAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val contactId: String,           // Contact lookup key
    val platform: String,            // "spotify" | "xbox"
    val platformUserId: String,      // Spotify user ID or Xbox XUID
    val platformUsername: String,    // Display name / Gamertag
    val linkedAt: Long,              // Timestamp
    val isVerified: Boolean = false  // If contact confirmed the link
)
```

---

## Technical Architecture

### API Integration

#### Spotify

```kotlin
interface SpotifyApi {
    // Get currently playing track for a user (requires they be a "friend" or public)
    // Note: Spotify's API doesn't expose friend activity directly
    // We need to poll the user's own playback if they share credentials
    // OR use the unofficial web API (risky, may break)

    @GET("v1/me/player/currently-playing")
    suspend fun getCurrentlyPlaying(
        @Header("Authorization") token: String
    ): Response<CurrentlyPlayingResponse>

    @GET("v1/me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 1
    ): Response<RecentlyPlayedResponse>

    @GET("v1/users/{user_id}")
    suspend fun getUserProfile(
        @Header("Authorization") token: String,
        @Path("user_id") userId: String
    ): Response<SpotifyUserProfile>
}
```

**Spotify Limitation:** The official API only lets you see YOUR OWN playback, not friends'.

**Workarounds:**
1. **Contact shares their refresh token** (complex, security concerns)
2. **Last.fm integration instead** - Last.fm has friend activity and scrobbles from Spotify
3. **Unofficial Spotify internal API** - Used by web app for friend activity (can break)

**Recommendation:** Consider Last.fm as an alternative/addition. Users connect Last.fm, we can see friends' scrobbles.

#### Xbox Live

```kotlin
interface XboxLiveApi {
    // Get presence for friends
    @GET("users/me/people")
    suspend fun getFriends(
        @Header("Authorization") xstsToken: String
    ): Response<FriendsResponse>

    @GET("users/xuid({xuid})/presence")
    suspend fun getPresence(
        @Header("Authorization") xstsToken: String,
        @Path("xuid") xuid: String
    ): Response<PresenceResponse>

    // Batch presence for multiple XUIDs
    @POST("users/batch/presence")
    suspend fun getBatchPresence(
        @Header("Authorization") xstsToken: String,
        @Body xuids: List<String>
    ): Response<List<PresenceResponse>>
}
```

Xbox Live presence data includes:
- Online state (Online, Away, Offline)
- Current game/app title
- Rich presence string ("In Match - Ranked")

### Status Repository

```kotlin
interface StatusRepository {
    fun getStatusForContact(contactId: String): Flow<ContactStatus?>
    fun getStatusForContacts(contactIds: List<String>): Flow<Map<String, ContactStatus>>
    suspend fun refreshStatus(contactId: String)
    suspend fun refreshAllLinkedContacts()
}

data class ContactStatus(
    val contactId: String,
    val platform: StatusPlatform,
    val activity: ActivityInfo,
    val timestamp: Instant,
    val isLive: Boolean  // Currently happening vs. recent
)

sealed class ActivityInfo {
    data class Music(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val albumArtUrl: String?,
        val durationMs: Long?,
        val progressMs: Long?
    ) : ActivityInfo()

    data class Gaming(
        val gameName: String,
        val gameImageUrl: String?,
        val richPresence: String?,  // "In Match - Ranked"
        val onlineStatus: OnlineStatus
    ) : ActivityInfo()
}

enum class StatusPlatform { SPOTIFY, XBOX, LASTFM }
enum class OnlineStatus { ONLINE, AWAY, BUSY, OFFLINE }
```

### Polling Strategy

Status data should be fetched efficiently:

```kotlin
class StatusPollingManager @Inject constructor(
    private val statusRepository: StatusRepository,
    private val appLifecycleTracker: AppLifecycleTracker
) {
    companion object {
        const val ACTIVE_CHAT_POLL_INTERVAL = 30_000L  // 30 seconds when viewing chat
        const val BACKGROUND_POLL_INTERVAL = 300_000L  // 5 minutes in background
        const val CONTACT_CARD_POLL_INTERVAL = 15_000L // 15 seconds on contact card
    }

    // Only poll for contacts with linked accounts
    // Batch requests where possible (Xbox supports batch)
    // Respect API rate limits
}
```

### Caching

```kotlin
@Entity(tableName = "status_cache")
data class StatusCacheEntity(
    @PrimaryKey
    val contactId: String,
    val platform: String,
    val activityJson: String,  // Serialized ActivityInfo
    val timestamp: Long,
    val isLive: Boolean,
    val expiresAt: Long  // Cache expiry
)
```

---

## Privacy & Security Considerations

### User Data

1. **OAuth tokens stored in EncryptedSharedPreferences**
2. **Refresh tokens never leave device** (except to respective APIs)
3. **Activity data cached locally, not synced to BlueBubbles server**

### Contact Privacy

1. **Linking is one-way** - You link their username, they don't know
2. **Requires public profile OR friendship** on platform
3. **Clear disclosure** in settings about what data is accessed

### Permissions Disclosure

Settings screen should include:

```
ℹ️ About Status Indicators

When you connect Spotify or Xbox:
• We access your friends' public activity
• Data stays on your device
• You can disconnect anytime

Contacts you link:
• Must have a public profile, OR
• Must be your friend on that platform
• They are NOT notified when you link them
```

---

## Implementation Phases

### Phase 1: Foundation
- [ ] Create `StatusIndicator` module structure
- [ ] Add `contact_linked_accounts` table and DAO
- [ ] Create settings screen for Status Indicators
- [ ] Implement secure token storage

### Phase 2: Xbox Live Integration
- [ ] Implement Xbox Live OAuth flow
- [ ] Create Xbox API client
- [ ] Implement presence fetching
- [ ] Add Xbox linking UI in contact card

### Phase 3: Spotify/Last.fm Integration
- [ ] Evaluate Spotify API limitations
- [ ] Implement Last.fm OAuth as alternative/supplement
- [ ] Create music status fetching
- [ ] Add music linking UI in contact card

### Phase 4: UI Integration
- [ ] Add status display to chat header
- [ ] Add activity section to contact card
- [ ] Implement status polling manager
- [ ] Add status caching layer

### Phase 5: Polish
- [ ] Animations for status changes
- [ ] Offline handling
- [ ] Rate limit handling with backoff
- [ ] Battery optimization (respect Doze mode)

---

## API Credentials Required

### Spotify

1. Create app at https://developer.spotify.com/dashboard
2. Configure redirect URI: `bothbubbles://spotify-callback`
3. Store Client ID in `local.properties` (not committed)

### Xbox Live / Microsoft Identity

1. Register app at https://portal.azure.com
2. Configure redirect URI: `bothbubbles://xbox-callback`
3. Store Client ID in `local.properties`

### Last.fm (Alternative)

1. Create app at https://www.last.fm/api/account/create
2. Store API key in `local.properties`

---

## Open Questions

1. **Spotify friend activity**: Official API doesn't support it. Use Last.fm instead? Use unofficial API?
2. **Contact verification**: Should we add a way for contacts to "confirm" the link?
3. **Multiple accounts**: Can a contact have both Spotify AND Last.fm linked?
4. **Group chats**: Show status for all members? Just the one you tap?
5. **Do Not Disturb**: Respect system DND for polling? Let user configure?

---

## File Structure

```
app/src/main/kotlin/com/bothbubbles/
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── dao/
│   │   │   │   ├── ContactLinkedAccountDao.kt
│   │   │   │   └── StatusCacheDao.kt
│   │   │   └── entity/
│   │   │       ├── ContactLinkedAccountEntity.kt
│   │   │       └── StatusCacheEntity.kt
│   │   └── prefs/
│   │       └── StatusPreferences.kt
│   ├── remote/
│   │   └── api/
│   │       ├── SpotifyApi.kt
│   │       ├── XboxLiveApi.kt
│   │       └── LastFmApi.kt
│   └── repository/
│       └── StatusRepository.kt
│
├── services/
│   └── status/
│       ├── StatusPollingManager.kt
│       ├── SpotifyAuthManager.kt
│       ├── XboxAuthManager.kt
│       └── LastFmAuthManager.kt
│
└── ui/
    ├── chat/
    │   └── components/
    │       └── ContactStatusIndicator.kt
    ├── contacts/
    │   └── components/
    │       ├── ActivitySection.kt
    │       └── LinkAccountSheet.kt
    └── settings/
        └── status/
            ├── StatusIndicatorSettingsScreen.kt
            └── StatusIndicatorSettingsViewModel.kt
```
