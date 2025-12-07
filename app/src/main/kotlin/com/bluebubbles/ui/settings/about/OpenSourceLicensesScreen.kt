package com.bluebubbles.ui.settings.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class OpenSourceLibrary(
    val name: String,
    val author: String,
    val license: String,
    val licenseUrl: String? = null,
    val projectUrl: String? = null
)

private val libraries = listOf(
    // AndroidX
    OpenSourceLibrary(
        name = "AndroidX Core KTX",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/androidx"
    ),
    OpenSourceLibrary(
        name = "AndroidX Lifecycle",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/androidx/releases/lifecycle"
    ),
    OpenSourceLibrary(
        name = "AndroidX Activity Compose",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/androidx/releases/activity"
    ),
    OpenSourceLibrary(
        name = "AndroidX Splash Screen",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/develop/ui/views/launch/splash-screen"
    ),

    // Compose
    OpenSourceLibrary(
        name = "Jetpack Compose",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/compose"
    ),
    OpenSourceLibrary(
        name = "Compose Material 3",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/compose/designsystems/material3"
    ),
    OpenSourceLibrary(
        name = "Compose Navigation",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/jetpack/compose/navigation"
    ),

    // Dependency Injection
    OpenSourceLibrary(
        name = "Hilt",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://dagger.dev/hilt/"
    ),

    // Networking
    OpenSourceLibrary(
        name = "Retrofit",
        author = "Square",
        license = "Apache License 2.0",
        projectUrl = "https://square.github.io/retrofit/"
    ),
    OpenSourceLibrary(
        name = "OkHttp",
        author = "Square",
        license = "Apache License 2.0",
        projectUrl = "https://square.github.io/okhttp/"
    ),
    OpenSourceLibrary(
        name = "Moshi",
        author = "Square",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/square/moshi"
    ),
    OpenSourceLibrary(
        name = "Socket.IO Client",
        author = "Socket.IO",
        license = "MIT License",
        projectUrl = "https://socket.io/"
    ),

    // Database
    OpenSourceLibrary(
        name = "Room",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/training/data-storage/room"
    ),
    OpenSourceLibrary(
        name = "DataStore",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/topic/libraries/architecture/datastore"
    ),

    // Images
    OpenSourceLibrary(
        name = "Coil",
        author = "Coil Contributors",
        license = "Apache License 2.0",
        projectUrl = "https://coil-kt.github.io/coil/"
    ),

    // Camera
    OpenSourceLibrary(
        name = "CameraX",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/training/camerax"
    ),

    // Media
    OpenSourceLibrary(
        name = "Media3 / ExoPlayer",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/guide/topics/media/media3"
    ),

    // ML Kit
    OpenSourceLibrary(
        name = "ML Kit Barcode Scanning",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developers.google.com/ml-kit/vision/barcode-scanning"
    ),
    OpenSourceLibrary(
        name = "ML Kit Smart Reply",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developers.google.com/ml-kit/language/smart-reply"
    ),
    OpenSourceLibrary(
        name = "ML Kit Entity Extraction",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developers.google.com/ml-kit/language/entity-extraction"
    ),

    // Background
    OpenSourceLibrary(
        name = "WorkManager",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developer.android.com/topic/libraries/architecture/workmanager"
    ),

    // Kotlin
    OpenSourceLibrary(
        name = "Kotlin Coroutines",
        author = "JetBrains",
        license = "Apache License 2.0",
        projectUrl = "https://kotlinlang.org/docs/coroutines-overview.html"
    ),
    OpenSourceLibrary(
        name = "Kotlin Serialization",
        author = "JetBrains",
        license = "Apache License 2.0",
        projectUrl = "https://kotlinlang.org/docs/serialization.html"
    ),

    // Firebase
    OpenSourceLibrary(
        name = "Firebase Cloud Messaging",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://firebase.google.com/docs/cloud-messaging"
    ),

    // Google Play Services
    OpenSourceLibrary(
        name = "Google Play Services Location",
        author = "Google",
        license = "Apache License 2.0",
        projectUrl = "https://developers.google.com/android/guides/setup"
    ),

    // Phone Number
    OpenSourceLibrary(
        name = "libphonenumber-android",
        author = "Michael Rozumyanskiy",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/AbandonedCart/libphonenumber-android"
    )
).sortedBy { it.name.lowercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "This app uses the following open source libraries:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(libraries) { library ->
                LicenseCard(
                    library = library,
                    onOpenUrl = { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LicenseCard(
    library: OpenSourceLibrary,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = library.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = library.license,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                library.projectUrl?.let { url ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clickable { onOpenUrl(url) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "View project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
