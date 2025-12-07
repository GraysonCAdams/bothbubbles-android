package com.bluebubbles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.ui.navigation.BlueBubblesNavHost
import com.bluebubbles.ui.navigation.ShareIntentData
import com.bluebubbles.ui.theme.BlueBubblesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be before super.onCreate()
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Parse share intent data
        val shareIntentData = parseShareIntent(intent)

        setContent {
            val isSetupComplete by settingsDataStore.isSetupComplete.collectAsState(initial = true)

            BlueBubblesTheme {
                BlueBubblesNavHost(
                    isSetupComplete = isSetupComplete,
                    shareIntentData = shareIntentData
                )
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
