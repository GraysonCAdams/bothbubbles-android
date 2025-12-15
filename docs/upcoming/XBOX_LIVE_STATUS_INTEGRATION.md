# Xbox Live Status Integration

## Overview

Display Xbox Live presence (current game, online status) for contacts linked to their Xbox Gamertag. Status appears in chat headers and contact cards.

**Server Required:** No - fully client-side using PKCE OAuth flow.

---

## Authentication Flow

### Microsoft Identity Platform + Xbox Live

```
┌─────────────────────────────────────────────────────────────────┐
│                        AUTHENTICATION                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Microsoft OAuth (PKCE)                                       │
│     ┌──────────┐      ┌──────────────┐      ┌──────────┐        │
│     │   App    │ ──── │ MS Login Page│ ──── │ Auth Code│        │
│     └──────────┘      └──────────────┘      └──────────┘        │
│                                                    │             │
│  2. Exchange for Microsoft Token                   ▼             │
│     ┌──────────┐      ┌──────────────┐      ┌──────────┐        │
│     │   App    │ ──── │ /token       │ ──── │ MS Token │        │
│     └──────────┘      └──────────────┘      └──────────┘        │
│                                                    │             │
│  3. Exchange for Xbox Live User Token              ▼             │
│     ┌──────────┐      ┌──────────────┐      ┌──────────┐        │
│     │   App    │ ──── │ user.auth.   │ ──── │ XBL Token│        │
│     │          │      │ xboxlive.com │      │          │        │
│     └──────────┘      └──────────────┘      └──────────┘        │
│                                                    │             │
│  4. Exchange for XSTS Token                        ▼             │
│     ┌──────────┐      ┌──────────────┐      ┌──────────┐        │
│     │   App    │ ──── │ xsts.auth.   │ ──── │XSTS Token│        │
│     │          │      │ xboxlive.com │      │ (final)  │        │
│     └──────────┘      └──────────────┘      └──────────┘        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Required Setup

1. **Register app at Azure Portal** (https://portal.azure.com)
   - App registrations → New registration
   - Name: "BothBubbles"
   - Supported account types: "Personal Microsoft accounts only"
   - Redirect URI: `bothbubbles://xbox-callback` (Mobile/Desktop)

2. **Configure API Permissions**
   - Add permission → Xbox Live → `XboxLive.signin`
   - No admin consent required for personal accounts

3. **Store Client ID**
   ```properties
   # local.properties (not committed)
   XBOX_CLIENT_ID=your-azure-app-client-id
   ```

---

## API Endpoints

### Token Exchange Endpoints

```
Microsoft OAuth:
  Authorize: https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize
  Token:     https://login.microsoftonline.com/consumers/oauth2/v2.0/token

Xbox Live:
  User Token: https://user.auth.xboxlive.com/user/authenticate
  XSTS Token: https://xsts.auth.xboxlive.com/xsts/authorize
```

### Xbox Live Data Endpoints

```
Profile:    https://profile.xboxlive.com
Presence:   https://userpresence.xboxlive.com
People:     https://peoplehub.xboxlive.com
```

### Authorization Header Format

```
Authorization: XBL3.0 x={userHash};{xstsToken}
```

---

## Implementation

### 1. Data Models

```kotlin
// Xbox authentication tokens
data class XboxAuthTokens(
    val microsoftAccessToken: String,
    val microsoftRefreshToken: String,
    val xstsToken: String,
    val userHash: String,  // "uhs" claim from XSTS response
    val xuid: String,      // User's Xbox ID
    val gamertag: String,
    val expiresAt: Instant
)

// Presence response from Xbox Live
data class XboxPresence(
    val xuid: String,
    val state: OnlineState,
    val lastSeen: LastSeenRecord?,
    val devices: List<DeviceRecord>?
)

enum class OnlineState { Online, Away, Busy, Offline }

data class DeviceRecord(
    val type: String,  // "XboxOne", "Win32", "iOS", etc.
    val titles: List<TitleRecord>
)

data class TitleRecord(
    val id: String,
    val name: String,
    val placement: String,  // "Full", "Background", etc.
    val state: String,      // "Active", "Inactive"
    val lastModified: Instant,
    val richPresence: String?  // "In Match - Ranked 2v2"
)

// For linking contacts
@Entity(tableName = "xbox_linked_contacts")
data class XboxLinkedContactEntity(
    @PrimaryKey
    val contactId: String,
    val xuid: String,
    val gamertag: String,
    val linkedAt: Long
)
```

### 2. Auth Manager

```kotlin
@Singleton
class XboxAuthManager @Inject constructor(
    private val context: Context,
    private val encryptedPrefs: EncryptedSharedPreferences,
    private val httpClient: OkHttpClient,
    private val moshi: Moshi
) {
    companion object {
        private const val MS_AUTHORIZE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        private const val MS_TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val XBL_USER_AUTH_URL =
            "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_AUTH_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize"

        private const val REDIRECT_URI = "bothbubbles://xbox-callback"
        private const val SCOPES = "XboxLive.signin XboxLive.offline_access openid"
    }

    private val clientId: String by lazy {
        BuildConfig.XBOX_CLIENT_ID
    }

    private var codeVerifier: String? = null

    /**
     * Generate authorization URL for OAuth flow
     */
    fun getAuthorizationUrl(): String {
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)

        return Uri.parse(MS_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * Handle OAuth callback and complete authentication
     */
    suspend fun handleCallback(code: String): Result<XboxAuthTokens> = safeCall {
        // Step 1: Exchange code for Microsoft token
        val msToken = exchangeCodeForMicrosoftToken(code)

        // Step 2: Exchange MS token for Xbox Live User Token
        val xblToken = exchangeForXboxLiveToken(msToken.accessToken)

        // Step 3: Exchange XBL token for XSTS token
        val xstsResponse = exchangeForXstsToken(xblToken)

        // Step 4: Get user profile (gamertag)
        val profile = fetchUserProfile(xstsResponse)

        val tokens = XboxAuthTokens(
            microsoftAccessToken = msToken.accessToken,
            microsoftRefreshToken = msToken.refreshToken,
            xstsToken = xstsResponse.token,
            userHash = xstsResponse.userHash,
            xuid = xstsResponse.xuid,
            gamertag = profile.gamertag,
            expiresAt = Instant.now().plusSeconds(msToken.expiresIn)
        )

        saveTokens(tokens)
        tokens
    }

    /**
     * Refresh tokens if expired
     */
    suspend fun getValidTokens(): XboxAuthTokens? {
        val tokens = loadTokens() ?: return null

        if (tokens.expiresAt.isBefore(Instant.now().plusMinutes(5))) {
            return refreshTokens(tokens)
        }

        return tokens
    }

    /**
     * Build authorization header for Xbox Live API calls
     */
    fun buildAuthHeader(tokens: XboxAuthTokens): String {
        return "XBL3.0 x=${tokens.userHash};${tokens.xstsToken}"
    }

    // ... PKCE helper methods, token storage, refresh logic
}
```

### 3. Xbox Live API Client

```kotlin
interface XboxLiveApi {

    @GET("users/me/profile")
    suspend fun getMyProfile(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "3"
    ): Response<ProfileResponse>

    @GET("users/xuid({xuid})/profile")
    suspend fun getUserProfile(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "3",
        @Path("xuid") xuid: String
    ): Response<ProfileResponse>

    @GET("users/me/people")
    suspend fun getFriendsList(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "5"
    ): Response<PeopleResponse>

    @GET("users/xuid({xuid})")
    suspend fun getPresence(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "3",
        @Path("xuid") xuid: String
    ): Response<XboxPresence>

    @POST("users/batch")
    suspend fun getBatchPresence(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "3",
        @Body request: BatchPresenceRequest
    ): Response<List<XboxPresence>>

    @GET("users/gt({gamertag})/profile")
    suspend fun searchByGamertag(
        @Header("Authorization") auth: String,
        @Header("x-xbl-contract-version") version: String = "3",
        @Path("gamertag") gamertag: String
    ): Response<ProfileResponse>
}

data class BatchPresenceRequest(
    val users: List<String>,  // List of XUIDs
    val level: String = "all"  // "user", "device", "title", "all"
)

// Retrofit module
@Module
@InstallIn(SingletonComponent::class)
object XboxNetworkModule {

    @Provides
    @XboxProfile
    fun provideProfileApi(okHttpClient: OkHttpClient, moshi: Moshi): XboxLiveApi {
        return Retrofit.Builder()
            .baseUrl("https://profile.xboxlive.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XboxLiveApi::class.java)
    }

    @Provides
    @XboxPresence
    fun providePresenceApi(okHttpClient: OkHttpClient, moshi: Moshi): XboxLiveApi {
        return Retrofit.Builder()
            .baseUrl("https://userpresence.xboxlive.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XboxLiveApi::class.java)
    }

    @Provides
    @XboxPeople
    fun providePeopleApi(okHttpClient: OkHttpClient, moshi: Moshi): XboxLiveApi {
        return Retrofit.Builder()
            .baseUrl("https://peoplehub.xboxlive.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XboxLiveApi::class.java)
    }
}

@Qualifier annotation class XboxProfile
@Qualifier annotation class XboxPresence
@Qualifier annotation class XboxPeople
```

### 4. Status Repository

```kotlin
@Singleton
class XboxStatusRepository @Inject constructor(
    private val authManager: XboxAuthManager,
    @XboxPresence private val presenceApi: XboxLiveApi,
    @XboxProfile private val profileApi: XboxLiveApi,
    private val linkedContactsDao: XboxLinkedContactsDao,
    private val statusCacheDao: StatusCacheDao
) {
    /**
     * Get presence for a linked contact
     */
    suspend fun getPresence(contactId: String): XboxPresence? {
        val tokens = authManager.getValidTokens() ?: return null
        val linked = linkedContactsDao.getByContactId(contactId) ?: return null

        // Check cache first
        val cached = statusCacheDao.get(contactId, "xbox")
        if (cached != null && !cached.isExpired()) {
            return cached.toXboxPresence()
        }

        // Fetch fresh
        val response = presenceApi.getPresence(
            auth = authManager.buildAuthHeader(tokens),
            xuid = linked.xuid
        )

        if (response.isSuccessful) {
            val presence = response.body()!!
            statusCacheDao.insert(presence.toCacheEntity(contactId))
            return presence
        }

        return null
    }

    /**
     * Batch fetch presence for all linked contacts
     */
    suspend fun getAllLinkedPresence(): Map<String, XboxPresence> {
        val tokens = authManager.getValidTokens() ?: return emptyMap()
        val linkedContacts = linkedContactsDao.getAll()

        if (linkedContacts.isEmpty()) return emptyMap()

        val xuids = linkedContacts.map { it.xuid }
        val response = presenceApi.getBatchPresence(
            auth = authManager.buildAuthHeader(tokens),
            request = BatchPresenceRequest(users = xuids)
        )

        if (!response.isSuccessful) return emptyMap()

        val presenceByXuid = response.body()!!.associateBy { it.xuid }

        return linkedContacts.mapNotNull { linked ->
            presenceByXuid[linked.xuid]?.let { presence ->
                linked.contactId to presence
            }
        }.toMap()
    }

    /**
     * Search for Xbox user by gamertag (for linking)
     */
    suspend fun searchGamertag(gamertag: String): ProfileResponse? {
        val tokens = authManager.getValidTokens() ?: return null

        val response = profileApi.searchByGamertag(
            auth = authManager.buildAuthHeader(tokens),
            gamertag = gamertag
        )

        return response.body()
    }

    /**
     * Link a contact to an Xbox account
     */
    suspend fun linkContact(contactId: String, xuid: String, gamertag: String) {
        linkedContactsDao.insert(
            XboxLinkedContactEntity(
                contactId = contactId,
                xuid = xuid,
                gamertag = gamertag,
                linkedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Unlink a contact
     */
    suspend fun unlinkContact(contactId: String) {
        linkedContactsDao.delete(contactId)
    }
}
```

---

## UI Components

### Settings Screen

```kotlin
@Composable
fun XboxSettingsSection(
    viewModel: StatusSettingsViewModel = hiltViewModel()
) {
    val isConnected by viewModel.isXboxConnected.collectAsState()
    val gamertag by viewModel.xboxGamertag.collectAsState()

    SettingsSection(title = "Xbox Live") {
        if (isConnected) {
            // Connected state
            SettingsItem(
                icon = Icons.Default.SportsEsports,
                title = "Connected as $gamertag",
                subtitle = "Tap to disconnect",
                onClick = { viewModel.disconnectXbox() }
            )

            SwitchSettingsItem(
                title = "Show friend activity",
                subtitle = "Display what games friends are playing",
                checked = viewModel.showXboxActivity.collectAsState().value,
                onCheckedChange = { viewModel.setShowXboxActivity(it) }
            )
        } else {
            // Not connected
            SettingsItem(
                icon = Icons.Default.SportsEsports,
                title = "Connect Xbox Account",
                subtitle = "Sign in to see friends' gaming activity",
                onClick = { viewModel.startXboxAuth() }
            )
        }
    }
}
```

### Link Contact Sheet

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkXboxSheet(
    contactName: String,
    onLink: (xuid: String, gamertag: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LinkXboxViewModel = hiltViewModel()
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Link Xbox Account",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Search for ${contactName}'s Gamertag:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter Gamertag") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() })
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isSearching -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
                searchResults != null -> {
                    SearchResultItem(
                        profile = searchResults!!,
                        onLink = {
                            onLink(searchResults!!.xuid, searchResults!!.gamertag)
                            onDismiss()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SearchResultItem(
    profile: ProfileResponse,
    onLink: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = profile.displayPicRaw,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.gamertag,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Gamerscore: ${profile.gamerScore}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onLink) {
                Text("Link")
            }
        }
    }
}
```

### Chat Header Status

```kotlin
@Composable
fun XboxStatusIndicator(
    presence: XboxPresence,
    modifier: Modifier = Modifier
) {
    val currentTitle = presence.devices
        ?.flatMap { it.titles }
        ?.firstOrNull { it.state == "Active" && it.placement == "Full" }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SportsEsports,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF107C10)  // Xbox green
        )

        Spacer(modifier = Modifier.width(4.dp))

        Column {
            Text(
                text = currentTitle?.name ?: presence.state.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            currentTitle?.richPresence?.let { richPresence ->
                Text(
                    text = richPresence,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } ?: run {
                val timeText = when (presence.state) {
                    OnlineState.Online -> "Online now"
                    else -> presence.lastSeen?.let {
                        "Last seen ${it.timestamp.toRelativeString()}"
                    } ?: presence.state.name
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

---

## Polling Strategy

```kotlin
@Singleton
class XboxStatusPoller @Inject constructor(
    private val repository: XboxStatusRepository,
    private val appLifecycleTracker: AppLifecycleTracker,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        const val FOREGROUND_POLL_INTERVAL = 60_000L   // 1 minute
        const val BACKGROUND_POLL_INTERVAL = 300_000L  // 5 minutes
        const val ACTIVE_CHAT_POLL_INTERVAL = 30_000L  // 30 seconds
    }

    private val _statusUpdates = MutableSharedFlow<Map<String, XboxPresence>>()
    val statusUpdates: SharedFlow<Map<String, XboxPresence>> = _statusUpdates

    private var pollingJob: Job? = null

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            appLifecycleTracker.foregroundState.collectLatest { isForeground ->
                val interval = if (isForeground) {
                    FOREGROUND_POLL_INTERVAL
                } else {
                    BACKGROUND_POLL_INTERVAL
                }

                while (isActive) {
                    val statuses = repository.getAllLinkedPresence()
                    _statusUpdates.emit(statuses)
                    delay(interval)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
```

---

## File Structure

```
app/src/main/kotlin/com/bothbubbles/
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── dao/
│   │   │   │   └── XboxLinkedContactsDao.kt
│   │   │   └── entity/
│   │   │       └── XboxLinkedContactEntity.kt
│   │   └── prefs/
│   │       └── XboxAuthPreferences.kt
│   ├── remote/
│   │   └── api/
│   │       ├── XboxLiveApi.kt
│   │       └── dto/
│   │           ├── XboxPresence.kt
│   │           ├── ProfileResponse.kt
│   │           └── BatchPresenceRequest.kt
│   └── repository/
│       └── XboxStatusRepository.kt
│
├── di/
│   └── XboxModule.kt
│
├── services/
│   └── status/
│       ├── XboxAuthManager.kt
│       └── XboxStatusPoller.kt
│
└── ui/
    ├── chat/
    │   └── components/
    │       └── XboxStatusIndicator.kt
    ├── contacts/
    │   └── components/
    │       └── LinkXboxSheet.kt
    └── settings/
        └── status/
            ├── StatusSettingsScreen.kt
            └── StatusSettingsViewModel.kt
```

---

## Implementation Checklist

### Phase 1: Authentication
- [ ] Register Azure app, get Client ID
- [ ] Add `XBOX_CLIENT_ID` to `local.properties` + BuildConfig
- [ ] Implement `XboxAuthManager` with PKCE flow
- [ ] Add encrypted storage for tokens
- [ ] Handle deep link callback `bothbubbles://xbox-callback`
- [ ] Implement token refresh logic

### Phase 2: API Integration
- [ ] Create `XboxLiveApi` Retrofit interface
- [ ] Add Xbox Retrofit modules with qualifiers
- [ ] Implement `XboxStatusRepository`
- [ ] Add `XboxLinkedContactEntity` + DAO
- [ ] Add gamertag search functionality

### Phase 3: Settings UI
- [ ] Create `StatusSettingsScreen`
- [ ] Add Xbox connect/disconnect flow
- [ ] Add toggle for showing activity

### Phase 4: Contact Linking
- [ ] Create `LinkXboxSheet` bottom sheet
- [ ] Add gamertag search UI
- [ ] Integrate into contact card screen

### Phase 5: Status Display
- [ ] Create `XboxStatusIndicator` composable
- [ ] Add to chat header (conditional)
- [ ] Add to contact card activity section
- [ ] Implement `XboxStatusPoller`

### Phase 6: Polish
- [ ] Handle auth errors gracefully
- [ ] Add offline caching
- [ ] Rate limit handling
- [ ] Battery optimization
