package com.bluebubbles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bluebubbles.data.local.prefs.SettingsDataStore
import com.bluebubbles.ui.navigation.BlueBubblesNavHost
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

        setContent {
            val isSetupComplete by settingsDataStore.isSetupComplete.collectAsState(initial = true)

            BlueBubblesTheme {
                BlueBubblesNavHost(isSetupComplete = isSetupComplete)
            }
        }
    }
}
