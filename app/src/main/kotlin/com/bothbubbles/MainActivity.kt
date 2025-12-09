package com.bothbubbles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.bothbubbles.ui.components.DeveloperConnectionOverlay
import com.bothbubbles.ui.navigation.BothBubblesNavHost
import com.bothbubbles.ui.navigation.Screen
import com.bothbubbles.ui.navigation.ShareIntentData
import com.bothbubbles.ui.navigation.StateRestorationData
import com.bothbubbles.ui.theme.BothBubblesTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        // Parse share intent data
        val shareIntentData = parseShareIntent(intent)

        // Check crash protection and get state restoration data synchronously
        // This needs to happen before setting content to determine start destination
        val stateRestorationData = runBlocking {
            val shouldSkipRestore = settingsDataStore.recordLaunchAndCheckCrashProtection()
            android.util.Log.d("StateRestore", "shouldSkipRestore=$shouldSkipRestore, shareIntent=${shareIntentData != null}")
            if (shouldSkipRestore || shareIntentData != null) {
                // Skip restoration if crash protection triggered or handling share intent
                android.util.Log.d("StateRestore", "Skipping restoration")
                null
            } else {
                // Try to restore previous state
                val lastChatGuid = settingsDataStore.lastOpenChatGuid.first()
                android.util.Log.d("StateRestore", "lastChatGuid=$lastChatGuid")
                if (lastChatGuid != null) {
                    val data = StateRestorationData(
                        chatGuid = lastChatGuid,
                        mergedGuids = settingsDataStore.lastOpenChatMergedGuids.first(),
                        scrollPosition = settingsDataStore.lastScrollPosition.first(),
                        scrollOffset = settingsDataStore.lastScrollOffset.first()
                    )
                    android.util.Log.d("StateRestore", "Restoring: $data")
                    data
                } else {
                    android.util.Log.d("StateRestore", "No chat to restore")
                    null
                }
            }
        }

        setContent {
            val isSetupComplete by settingsDataStore.isSetupComplete.collectAsState(initial = true)
            val developerModeEnabled by settingsDataStore.developerModeEnabled.collectAsState(initial = false)
            val connectionMode by connectionModeManager.currentMode.collectAsState()
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
                        stateRestorationData = stateRestorationData
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
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: return null

                // Check if it's an SMS/MMS scheme intent (not a share sheet intent)
                val data = intent.data
                if (data != null && data.scheme in listOf("sms", "smsto", "mms", "mmsto")) {
                    return null
                }

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
                        if (text != null) ShareIntentData(sharedText = text) else null
                    }
                    else -> {
                        // Handle media (image, video, audio, files)
                        val uri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                        if (uri != null) ShareIntentData(sharedUris = listOf(uri)) else null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) ShareIntentData(sharedUris = uris) else null
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
}
