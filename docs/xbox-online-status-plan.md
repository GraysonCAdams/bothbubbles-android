# Xbox Online Status Indicators - Implementation Plan

## Overview

Add online status indicators from Xbox Live (and future platforms) to contact avatars. When a contact's Xbox friend is online, show a green dot on their avatar. Tapping the dot shows a tooltip with their current activity (e.g., "Playing Halo Infinite").

## Architecture Decision: Scalable Multi-Platform

**Decision**: Build with abstractions from day one, implement Xbox first.

This approach adds ~20% more upfront work but makes adding PSN/Steam/Discord trivial later. The key abstractions:
- `PresenceProvider` interface (implemented by `XboxPresenceProvider`)
- `PresenceRegistry` for coordinating multiple providers
- Unified `ExternalPresenceEntity` table for all platforms

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Contact Linking | Manual only | User explicitly maps Xbox friends to contacts |
| Indicator Design | Simple green dot | No platform icon, consistent with existing UI |
| Tooltip Trigger | Tap | Single tap on green dot shows status |
| Polling Frequency | 5 minutes minimum | Battery-conscious, background sync only |

## Xbox API Requirements

### One-Time Developer Setup (You do this once)

1. Go to [Azure Portal](https://portal.azure.com/) → Azure Active Directory → App registrations
2. Create new registration:
   - Name: "BothBubbles" (or your app name)
   - Supported account types: **"Personal Microsoft accounts only"**
   - Redirect URI: `https://login.live.com/oauth20_desktop.srf` (for mobile apps)
3. Note the **Application (client) ID**
4. Under "Authentication":
   - Enable "Allow public client flows" = Yes (required for mobile apps)
   - No client secret needed (public client with PKCE)

**Users do NOT need to register anything** - they just log in with their Microsoft/Xbox account.

### Client ID (No Secret Required)

Client ID is hardcoded in source - it's public by design (no client secret for mobile PKCE apps):

```kotlin
// services/presence/xbox/XboxConfig.kt
object XboxConfig {
    const val CLIENT_ID = "your-azure-client-id"
    const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    const val SCOPE = "XboxLive.signin XboxLive.offline_access"
}
```

### Authentication Flow (No Server Required)

The app uses **public client OAuth with PKCE** - no backend server needed:

```
1. User taps "Connect Xbox" in settings
2. App opens WebView to: login.live.com/oauth20_authorize.srf
   - client_id: YOUR_APP_CLIENT_ID (embedded in app)
   - scope: XboxLive.signin XboxLive.offline_access
   - response_type: code
   - code_challenge: [PKCE challenge]
   - redirect_uri: https://login.live.com/oauth20_desktop.srf
3. User logs in with Microsoft account
4. WebView redirects with authorization code
5. App exchanges code for tokens (POST to login.live.com/oauth20_token.srf)
   - Returns: access_token, refresh_token
6. App exchanges access_token for Xbox User Token (user.auth.xboxlive.com)
7. App exchanges User Token for XSTS Token (xsts.auth.xboxlive.com)
8. XSTS Token used for all Xbox API calls
```

### Token Storage & Refresh (Offline-Capable)

All tokens stored locally in **EncryptedSharedPreferences**:

| Token | Lifetime | Stored Locally | Refresh Method |
|-------|----------|----------------|----------------|
| Access Token | ~1 hour | Yes | Use refresh_token |
| Refresh Token | ~90 days (sliding) | Yes | Auto-extends on use |
| Xbox User Token | ~24 hours | Yes | Re-exchange access token |
| XSTS Token | ~24 hours | Yes | Re-exchange user token |

**When app reopens after being closed:**
1. Check if XSTS token expired
2. If expired, check if refresh_token valid (90-day window)
3. If valid: silently refresh all tokens (no user interaction)
4. If expired (90+ days inactive): prompt user to re-login once

**This is the same pattern used by:** Spotify, Discord, Twitch, and all mobile OAuth apps.

### Key Endpoints
- **Presence**: `GET userpresence.xboxlive.com/users/xuid({xuid})` - Get online status
- **Friends**: `GET social.xboxlive.com/users/me/friends` - Get friends list
- **Profile**: `POST profile.xboxlive.com/users/batch/profile/settings` - Gamertag lookup

## Database Schema

### New Table: `external_presence`

```sql
CREATE TABLE external_presence (
    platform TEXT NOT NULL,              -- "XBOX", "PLAYSTATION", "STEAM", "DISCORD"
    platform_user_id TEXT NOT NULL,      -- XUID, PSN ID, Steam64 ID, etc.
    gamertag TEXT NOT NULL,
    avatar_url TEXT,
    presence_state TEXT NOT NULL,        -- "ONLINE", "AWAY", "BUSY", "PLAYING", "OFFLINE"
    activity_text TEXT,                  -- "Playing Halo Infinite"
    status_text TEXT,                    -- Custom status message
    presence_timestamp INTEGER,
    last_updated INTEGER NOT NULL,
    mapped_handle_id INTEGER,            -- FK to handles table
    PRIMARY KEY (platform, platform_user_id),
    FOREIGN KEY (mapped_handle_id) REFERENCES handles(id) ON DELETE SET NULL
);

CREATE INDEX idx_presence_handle ON external_presence(mapped_handle_id);
CREATE INDEX idx_presence_platform ON external_presence(platform);
```

## New Files Required

### Core Abstractions (`app/.../services/presence/`)
| File | Description |
|------|-------------|
| `PresenceProvider.kt` | Interface for platform-specific providers |
| `PresenceRegistry.kt` | Registry of all presence providers |
| `PresenceService.kt` | Orchestrator service interface |
| `PresenceServiceImpl.kt` | Implementation aggregating all providers |
| `PresenceSyncWorker.kt` | Background sync via WorkManager |

### Xbox Implementation (`app/.../services/presence/xbox/`)
| File | Description |
|------|-------------|
| `XboxTokenStorage.kt` | EncryptedSharedPreferences for tokens |
| `XboxPresenceProvider.kt` | Xbox-specific PresenceProvider |
| `XboxRateLimiter.kt` | Rate limiting for Xbox API |

### Network Layer (`core/network/.../api/`)
| File | Description |
|------|-------------|
| `XboxApi.kt` | Retrofit interface |
| `dto/XboxDtos.kt` | Moshi DTOs |

### Data Layer
| File | Description |
|------|-------------|
| `core/model/.../entity/ExternalPresenceEntity.kt` | Room entity |
| `app/.../data/local/db/dao/PresenceDao.kt` | DAO |
| `app/.../data/repository/PresenceRepository.kt` | Repository |

### UI Layer (`app/.../ui/settings/presence/`)
| File | Description |
|------|-------------|
| `ConnectedAccountsScreen.kt` | Hub for all platform connections |
| `XboxSettingsScreen.kt` | Xbox login + friend mapping |
| `XboxSettingsViewModel.kt` | ViewModel |
| `XboxLoginWebView.kt` | OAuth WebView |

### Error Handling
| File | Description |
|------|-------------|
| `app/.../util/error/PresenceError.kt` | Sealed error class |

### Models (`core/model/.../presence/`)
| File | Description |
|------|-------------|
| `PresencePlatform.kt` | Platform enum |
| `PresenceState.kt` | Status enum |
| `ContactPresence.kt` | Unified presence model |

## UI Components

### Avatar Enhancement
Extend `AvatarWithStatus` in `AvatarVariants.kt`:
- Green dot for online (existing pattern)
- Add `onStatusClick` callback for tap handling
- Pass `statusText` for tooltip content

### Status Tooltip
`PresenceTooltip.kt` - Simple popup showing:
- Platform name + status text (e.g., "Xbox: Playing Halo Infinite")
- Dismisses on tap outside
- Uses Material 3 `PlainTooltip` or lightweight `Popup`

### Settings Flow
1. Settings → Connected Accounts (new section)
2. Connected Accounts → Xbox tile (shows login or connected state)
3. Xbox Settings:
   - Login via WebView (Microsoft OAuth)
   - Sync friends list
   - Manual mapping: List of Xbox friends, tap to link to contact

## Implementation Phases

### Phase 1: Core Infrastructure
1. Create presence models (`PresencePlatform`, `PresenceState`, `ContactPresence`)
2. Create `ExternalPresenceEntity` + migration
3. Create `PresenceDao` + `PresenceRepository`
4. Create `PresenceError` sealed class
5. Create `PresenceProvider` interface
6. Create `PresenceRegistry` interface

### Phase 2: Xbox Provider
1. Create `XboxTokenStorage` (follow Life360TokenStorage pattern)
2. Create `XboxApi` Retrofit interface + DTOs
3. Create `XboxRateLimiter`
4. Create `XboxPresenceProvider`
5. Wire up DI

### Phase 3: Service Layer
1. Create `PresenceService` interface
2. Create `PresenceServiceImpl`
3. Create `PresenceSyncWorker`
4. Schedule worker on app init

### Phase 4: Settings UI
1. Create `XboxLoginWebView` (follow Life360LoginWebView pattern)
2. Create `XboxSettingsScreen` + ViewModel
3. Create `ConnectedAccountsScreen`
4. Add navigation routes
5. Add to settings menu

### Phase 5: Avatar Integration
1. Enhance `AvatarWithStatus` for presence tap
2. Create `PresenceTooltip` component
3. Update `ConversationTile` to observe presence
4. Update `ChatTopBar` to show presence

## Key Files to Read Before Implementation

1. `app/.../services/life360/Life360TokenStorage.kt` - Token storage pattern
2. `app/.../services/life360/Life360ServiceImpl.kt` - Service implementation pattern
3. `app/.../ui/settings/life360/Life360SettingsScreen.kt` - Settings UI pattern
4. `app/.../ui/settings/life360/Life360LoginWebView.kt` - OAuth WebView pattern
5. `core/model/.../entity/Life360MemberEntity.kt` - Entity with FK pattern
6. `app/.../ui/components/common/AvatarVariants.kt` - Avatar status indicator
7. `app/.../util/error/Life360Error.kt` - Error handling pattern

## Polling & Sync Strategy

- **Background sync**: WorkManager every 15 minutes (Android minimum)
- **Foreground sync**: Every 5 minutes when app is open
- **Manual refresh**: Pull-to-refresh on Xbox friends list
- **On app resume**: Sync once when returning to foreground

## Complexity Assessment

| Aspect | Complexity | Notes |
|--------|------------|-------|
| Xbox OAuth | Medium-High | Multi-step token exchange (OAuth → User Token → XSTS) |
| Token Refresh | Medium | Need to handle access/refresh/XSTS token lifetimes |
| Presence API | Low | Simple GET requests once authenticated |
| Contact Mapping | Low | Manual-only simplifies implementation |
| UI Integration | Low | Extend existing avatar components |
| Multi-Platform Architecture | Low | Abstractions add minimal overhead |

**Total Estimate**: Medium complexity. Xbox OAuth is the trickiest part - requires understanding the Microsoft → Xbox token exchange flow.
