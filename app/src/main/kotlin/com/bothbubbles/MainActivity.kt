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

        return when (intent.action) {
            // Voice command intents (Google Assistant, Android Auto "send a message to...")
            Intent.ACTION_SENDTO -> {
                val data = intent.data ?: return null
                if (data.scheme !in listOf("sms", "smsto", "mms", "mmsto")) return null

                // Extract recipient from URI (format: sms:+1234567890 or smsto:+1234567890)
                val recipient = data.schemeSpecificPart
                    ?.takeWhile { it != '?' }  // Remove query params
                    ?.takeIf { it.isNotBlank() }

                // Extract message body from query param or extras
                val messageBody = data.getQueryParameter("body")
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

                    val messageBody = data.getQueryParameter("body")
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
                        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
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
                        val uri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
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
                val uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                val directShareChatGuid = intent.getStringExtra(NotificationChannelManager.EXTRA_CHAT_GUID)
                    ?: extractChatGuidFromShortcutId(intent)
                if (!uris.isNullOrEmpty() || directShareChatGuid != null) {
                    ShareIntentData(
                        sharedUris = uris ?: emptyList(),
                        directShareChatGuid = directShareChatGuid
                    )
                } else null
            }
            else -> null
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
        val messageBody = data.getQueryParameter("body")
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
}
