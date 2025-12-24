package com.bothbubbles

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.bothbubbles.data.local.prefs.SettingsDataStore
import com.bothbubbles.services.developer.ConnectionModeManager
import com.bothbubbles.ui.components.common.DeveloperConnectionOverlay
import com.bothbubbles.ui.navigation.BothBubblesNavHost
import com.bothbubbles.ui.navigation.NotificationDeepLinkData
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.ui.navigation.ShareIntentData
import com.bothbubbles.ui.navigation.StateRestorationData
import com.bothbubbles.services.notifications.NotificationChannelManager
import com.bothbubbles.services.notifications.NotificationService
import com.bothbubbles.services.voice.VoiceMessageService
import com.bothbubbles.ui.theme.BothBubblesTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var connectionModeManager: ConnectionModeManager

    @Inject
    lateinit var unifiedChatDao: com.bothbubbles.data.local.db.dao.UnifiedChatDao

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be before super.onCreate()
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Sync window background with dynamic color theme on Android 12+
        // This prevents flash when using Material You dynamic colors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isDark = resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dynamicScheme = if (isDark) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
            window.decorView.setBackgroundColor(dynamicScheme.background.toArgb())
        }

        // Check for headless voice command (has both recipient AND message body)
        // Route to VoiceMessageService for seamless Google Assistant/Android Auto experience
        if (shouldHandleHeadless(intent)) {
            startVoiceMessageService(intent)
            finish()
            return
        }

        // Parse share intent data
        val shareIntentData = parseShareIntent(intent)

        // Parse notification deep link data (when user taps a notification)
        val notificationDeepLinkData = parseNotificationDeepLink(intent)

        // Check crash protection and get state restoration data synchronously
        // This needs to happen before setting content to determine start destination
        // Bounded by 2 second timeout to prevent ANR if DataStore is slow
        val stateRestorationData = runBlocking {
            withTimeoutOrNull(2000L) {
                val shouldSkipRestore = settingsDataStore.recordLaunchAndCheckCrashProtection()
                Timber.tag("StateRestore").d("shouldSkipRestore=$shouldSkipRestore, shareIntent=${shareIntentData != null}, notificationDeepLink=${notificationDeepLinkData != null}")
                if (shouldSkipRestore || shareIntentData != null || notificationDeepLinkData != null) {
                    // Skip restoration if crash protection triggered, handling share intent, or notification deep link
                    Timber.tag("StateRestore").d("Skipping restoration")
                    null
                } else {
                    // Try to restore previous state
                    val lastChatGuid = settingsDataStore.lastOpenChatGuid.first()
                    Timber.tag("StateRestore").d("lastChatGuid=$lastChatGuid")
                    if (lastChatGuid != null) {
                        val data = StateRestorationData(
                            chatGuid = lastChatGuid,
                            mergedGuids = settingsDataStore.lastOpenChatMergedGuids.first(),
                            scrollPosition = settingsDataStore.lastScrollPosition.first(),
                            scrollOffset = settingsDataStore.lastScrollOffset.first()
                        )
                        Timber.tag("StateRestore").d("Restoring: $data")
                        data
                    } else {
                        Timber.tag("StateRestore").d("No chat to restore")
                        null
                    }
                }
            } // Returns null if timeout - app starts fresh without state restoration
        }

        setContent {
            val isSetupComplete by settingsDataStore.isSetupComplete.collectAsStateWithLifecycle(initialValue = true)
            val developerModeEnabled by settingsDataStore.developerModeEnabled.collectAsStateWithLifecycle(initialValue = false)
            val connectionMode by connectionModeManager.currentMode.collectAsStateWithLifecycle()
            val navController = rememberNavController()

            // Clear crash protection after app has been stable for 30 seconds
            LaunchedEffect(Unit) {
                delay(30_000L)
                settingsDataStore.clearLaunchTimestamps()
            }

            BothBubblesTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BothBubblesNavHost(
                        navController = navController,
                        isSetupComplete = isSetupComplete,
                        shareIntentData = shareIntentData,
                        stateRestorationData = stateRestorationData,
                        notificationDeepLinkData = notificationDeepLinkData
                    )

                    // Developer mode connection overlay
                    DeveloperConnectionOverlay(
                        isVisible = developerModeEnabled && isSetupComplete,
                        connectionMode = connectionMode,
                        onTap = {
                            navController.navigate(Screen.DeveloperEventLog)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle new share intents when activity is already running
        val shareIntentData = parseShareIntent(intent)
        if (shareIntentData != null) {
            // Recreate to handle the new share intent
            recreate()
        }
    }

    private fun parseShareIntent(intent: Intent?): ShareIntentData? {
        if (intent == null) return null

        // Debug logging for share intent diagnosis
        Timber.d("parseShareIntent: action=${intent.action}, type=${intent.type}, data=${intent.data}")
        Timber.d("parseShareIntent: extras=${intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }}")
        Timber.d("parseShareIntent: clipData itemCount=${intent.clipData?.itemCount}, clipData[0]=${intent.clipData?.getItemAt(0)?.let { "text=${it.text}, uri=${it.uri}" }}")

        return when (intent.action) {
            // Voice command intents (Google Assistant, Android Auto "send a message to...")
            Intent.ACTION_SENDTO -> {
                val data = intent.data ?: return null

                // Handle imto scheme for group chat IM contacts
                // Format: imto://BothBubbles/{unifiedChatId} or imto:BothBubbles:{unifiedChatId}
                // Note: Android/Google Contacts may lowercase the host, so compare case-insensitively
                if (data.scheme == "imto") {
                    val host = data.host ?: data.schemeSpecificPart?.substringBefore(":")
                    if (host?.equals("BothBubbles", ignoreCase = true) == true) {
                        // Extract unified chat ID - could be in path or after second colon
                        val unifiedChatId = data.pathSegments?.firstOrNull()
                            ?: data.schemeSpecificPart?.substringAfter(":")?.substringAfter(":")

                        // Extract message body from extras
                        val messageBody = intent.getStringExtra("sms_body")
                            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

                        if (!unifiedChatId.isNullOrBlank()) {
                            // Resolve unified chat ID to chat GUID (sourceId)
                            val chatGuid = resolveUnifiedChatToGuid(unifiedChatId)
                            if (chatGuid != null) {
                                Timber.d("Voice command IM intent: unifiedId=$unifiedChatId, chatGuid=$chatGuid, body=${messageBody?.take(20)}...")
                                return ShareIntentData(
                                    sharedText = messageBody,
                                    directShareChatGuid = chatGuid  // Direct to group chat
                                )
                            } else {
                                Timber.w("Could not resolve unified chat ID: $unifiedChatId")
                            }
                        }
                    }
                    return null
                }

                if (data.scheme !in listOf("sms", "smsto", "mms", "mmsto")) return null

                // Extract recipient from URI (format: sms:+1234567890 or smsto:+1234567890)
                val recipient = data.schemeSpecificPart
                    ?.takeWhile { it != '?' }  // Remove query params
                    ?.takeIf { it.isNotBlank() }

                // Extract message body from query param or extras
                val messageBody = safeGetQueryParameter(data, "body")
                    ?: intent.getStringExtra("sms_body")
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)

                if (recipient != null) {
                    Timber.d("Voice command intent: recipient=$recipient, body=${messageBody?.take(20)}...")
                    ShareIntentData(
                        sharedText = messageBody,
                        recipientAddress = recipient
                    )
                } else null
            }

            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: return null

                // Check if it's an SMS/MMS scheme intent with recipient (voice command via ACTION_SEND)
                val data = intent.data
                if (data != null && data.scheme in listOf("sms", "smsto", "mms", "mmsto")) {
                    // Extract recipient from URI
                    val recipient = data.schemeSpecificPart
                        ?.takeWhile { it != '?' }
                        ?.takeIf { it.isNotBlank() }

                    val messageBody = safeGetQueryParameter(data, "body")
                        ?: intent.getStringExtra("sms_body")
                        ?: intent.getStringExtra(Intent.EXTRA_TEXT)

                    if (recipient != null) {
                        Timber.d("Voice command via ACTION_SEND: recipient=$recipient")
                        return ShareIntentData(
                            sharedText = messageBody,
                            recipientAddress = recipient
                        )
                    }
                    // If no recipient in URI, fall through to regular share handling
                }

                // Check if this is a direct share from a sharing shortcut
                // Android Direct Share API (API 29+) puts shortcut ID in EXTRA_SHORTCUT_ID,
                // not the extras from the shortcut's intent template
                val directShareChatGuid = intent.getStringExtra(NotificationChannelManager.EXTRA_CHAT_GUID)
                    ?: extractChatGuidFromShortcutId(intent)

                when {
                    mimeType.startsWith("text/") -> {
                        var sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

                        // Fallback: Check ClipData for text (some apps like TikTok use this)
                        if (sharedText == null) {
                            sharedText = intent.clipData?.let { clipData ->
                                if (clipData.itemCount > 0) {
                                    val item = clipData.getItemAt(0)
                                    // Try text first, then coerce URI to text
                                    item.text?.toString() ?: item.uri?.toString()
                                } else null
                            }
                        }

                        // Combine subject and text if both present
                        val text = when {
                            sharedSubject != null && sharedText != null -> "$sharedSubject\n$sharedText"
                            sharedSubject != null -> sharedSubject
                            else -> sharedText
                        }
                        if (text != null || directShareChatGuid != null) {
                            ShareIntentData(
                                sharedText = text,
                                directShareChatGuid = directShareChatGuid
                            )
                        } else null
                    }
                    else -> {
                        // Handle media (image, video, audio, files)
                        var uri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)

                        // Fallback: Check ClipData for URI (some apps use this instead of EXTRA_STREAM)
                        if (uri == null) {
                            uri = intent.clipData?.let { clipData ->
                                if (clipData.itemCount > 0) {
                                    clipData.getItemAt(0).uri
                                } else null
                            }
                        }

                        if (uri != null || directShareChatGuid != null) {
                            ShareIntentData(
                                sharedUris = listOfNotNull(uri),
                                directShareChatGuid = directShareChatGuid
                            )
                        } else null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                var uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                val directShareChatGuid = intent.getStringExtra(NotificationChannelManager.EXTRA_CHAT_GUID)
                    ?: extractChatGuidFromShortcutId(intent)

                // Fallback: Check ClipData for URIs
                if (uris.isNullOrEmpty()) {
                    uris = intent.clipData?.let { clipData ->
                        ArrayList<Uri>().apply {
                            for (i in 0 until clipData.itemCount) {
                                clipData.getItemAt(i).uri?.let { add(it) }
                            }
                        }.takeIf { it.isNotEmpty() }
                    }
                }

                if (!uris.isNullOrEmpty() || directShareChatGuid != null) {
                    ShareIntentData(
                        sharedUris = uris ?: emptyList(),
                        directShareChatGuid = directShareChatGuid
                    )
                } else null
            }
            else -> null
        }.also { result ->
            Timber.d("parseShareIntent result: sharedText=${result?.sharedText?.take(100)}, uris=${result?.sharedUris?.size}, directShare=${result?.directShareChatGuid}, recipient=${result?.recipientAddress}")
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        key: String
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, T::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> getParcelableArrayListExtraCompat(
        intent: Intent,
        key: String
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }

    /**
     * Parse notification deep link data from intent.
     * Returns NotificationDeepLinkData if the intent contains EXTRA_CHAT_GUID from a notification tap.
     */
    private fun parseNotificationDeepLink(intent: Intent?): NotificationDeepLinkData? {
        if (intent == null) return null

        // Validate chatGuid is not blank
        val chatGuid = intent.getStringExtra(NotificationChannelManager.EXTRA_CHAT_GUID)
            ?.takeIf { it.isNotBlank() } ?: return null

        // Validate optional fields are not blank
        val messageGuid = intent.getStringExtra(NotificationChannelManager.EXTRA_MESSAGE_GUID)
            ?.takeIf { it.isNotBlank() }
        val mergedGuids = intent.getStringExtra(NotificationChannelManager.EXTRA_MERGED_GUIDS)
            ?.takeIf { it.isNotBlank() }

        // Debug: Log notification deep link data to diagnose wrong-chat-navigation issues
        Timber.d("NOTIFICATION_DEBUG: Parsed deep link - chatGuid=$chatGuid, messageGuid=$messageGuid, mergedGuids=$mergedGuids")

        return NotificationDeepLinkData(
            chatGuid = chatGuid,
            messageGuid = messageGuid,
            mergedGuids = mergedGuids
        )
    }

    /**
     * Extract chat GUID from Android Direct Share shortcut ID.
     *
     * When sharing via Android's share sheet to a direct share target (API 29+),
     * Android does NOT preserve extras from the shortcut's intent template.
     * Instead, it provides the shortcut ID via Intent.EXTRA_SHORTCUT_ID.
     *
     * Our shortcut IDs are formatted as "share_{chatGuid}", so we extract
     * the chat GUID by removing the prefix.
     */
    private fun extractChatGuidFromShortcutId(intent: Intent): String? {
        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            ?: return null

        val prefix = "share_"
        return if (shortcutId.startsWith(prefix)) {
            shortcutId.removePrefix(prefix).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /**
     * Check if this intent should be handled headlessly (no UI).
     *
     * For a seamless Google Assistant/Android Auto experience, we send messages
     * without showing UI when BOTH recipient AND message body are provided.
     * This matches the behavior of Google Messages and other native messaging apps.
     */
    private fun shouldHandleHeadless(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_SENDTO) return false

        val data = intent.data ?: return false
        if (data.scheme !in listOf("sms", "smsto", "mms", "mmsto")) return false

        // Must have recipient
        val recipient = data.schemeSpecificPart
            ?.takeWhile { it != '?' }
            ?.takeIf { it.isNotBlank() }
            ?: return false

        // Must have message body for headless send
        val messageBody = safeGetQueryParameter(data, "body")
            ?: intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

        val hasBody = !messageBody.isNullOrBlank()

        if (hasBody) {
            Timber.d("Headless voice command detected: recipient=$recipient")
        }

        return hasBody
    }

    /**
     * Start the VoiceMessageService to handle the voice command headlessly.
     */
    private fun startVoiceMessageService(intent: Intent?) {
        if (intent == null) return

        val serviceIntent = Intent(this, VoiceMessageService::class.java).apply {
            action = intent.action
            data = intent.data
            // Copy relevant extras
            intent.getStringExtra("sms_body")?.let { putExtra("sms_body", it) }
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

        startService(serviceIntent)
        Timber.d("Started VoiceMessageService for headless send")
    }

    /**
     * Safely extract a query parameter from a URI.
     *
     * For opaque URIs like "sms:+1234567890?body=hello", getQueryParameter()
     * throws UnsupportedOperationException. This helper handles both hierarchical
     * and opaque URIs.
     */
    private fun safeGetQueryParameter(uri: android.net.Uri, key: String): String? {
        return try {
            if (uri.isHierarchical) {
                uri.getQueryParameter(key)
            } else {
                // For opaque URIs, manually parse query from schemeSpecificPart
                uri.schemeSpecificPart
                    ?.substringAfter("?", "")
                    ?.split("&")
                    ?.find { it.startsWith("$key=") }
                    ?.substringAfter("$key=")
                    ?.let { android.net.Uri.decode(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve a unified chat ID to the primary chat GUID (sourceId).
     * Used for Google Assistant/Android Auto group chat intents.
     */
    private fun resolveUnifiedChatToGuid(unifiedChatId: String): String? {
        return try {
            runBlocking {
                unifiedChatDao.getById(unifiedChatId)?.sourceId
            }
        } catch (e: Exception) {
            Timber.e(e, "Error resolving unified chat ID to chat GUID")
            null
        }
    }
}
