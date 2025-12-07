package com.bothbubbles.ui.settings.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.theme.KumbhSansFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onOpenSourceLicensesClick: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App info header
            SettingsCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Message,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Both")
                            }
                            append("Bubbles")
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = KumbhSansFamily)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Version ${uiState.appVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.serverVersion != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Server: ${uiState.serverVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Links
            SettingsCard {
                Column {
                    ListItem(
                        headlineContent = { Text("Website") },
                        supportingContent = { Text("bluebubbles.app") },
                        leadingContent = {
                            Icon(Icons.Default.Language, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://bluebubbles.app"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Discord") },
                        supportingContent = { Text("Join our community") },
                        leadingContent = {
                            Icon(Icons.Default.Forum, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/yC4wr38"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("GitHub") },
                        supportingContent = { Text("View source code") },
                        leadingContent = {
                            Icon(Icons.Default.Code, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BlueBubblesApp/bluebubbles-app"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Documentation") },
                        supportingContent = { Text("Setup guides and FAQ") },
                        leadingContent = {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.bluebubbles.app"))
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // Legal
            SettingsCard {
                Column {
                    ListItem(
                        headlineContent = { Text("Privacy Policy") },
                        leadingContent = {
                            Icon(Icons.Default.PrivacyTip, contentDescription = null)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://bluebubbles.app/privacy"))
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Open source licenses") },
                        leadingContent = {
                            Icon(Icons.Default.Description, contentDescription = null)
                        },
                        modifier = Modifier.clickable(onClick = onOpenSourceLicensesClick)
                    )
                }
            }

            // Made with love
            Text(
                text = buildAnnotatedString {
                    append("Made with ❤️ by the ")
                    withStyle(SpanStyle(fontFamily = KumbhSansFamily, fontWeight = FontWeight.Bold)) {
                        append("Both")
                    }
                    withStyle(SpanStyle(fontFamily = KumbhSansFamily)) {
                        append("Bubbles")
                    }
                    append(" team")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
