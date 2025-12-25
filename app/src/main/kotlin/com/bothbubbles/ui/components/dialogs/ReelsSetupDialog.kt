package com.bothbubbles.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Configuration for enabling the Reels feature.
 *
 * @param includeVideoAttachments Include regular video attachments in Reels
 * @param includeTikToks Enable TikTok video downloading for Reels
 * @param includeInstagrams Enable Instagram Reel downloading for Reels
 * @param downloadOnCellular Allow downloading over cellular data
 */
data class ReelsSetupConfig(
    val includeVideoAttachments: Boolean = true,
    val includeTikToks: Boolean = false,
    val includeInstagrams: Boolean = false,
    val downloadOnCellular: Boolean = true
)

/**
 * Dialog shown when user taps the Reels button and Reels is not yet enabled.
 * Allows configuring which video sources to include and network preferences.
 *
 * Requires at least one source to be enabled before proceeding.
 *
 * @param visible Whether the dialog is currently visible
 * @param onConfirm Called with the selected configuration when user confirms
 * @param onDismiss Called when user cancels or dismisses the dialog
 */
@Composable
fun ReelsSetupDialog(
    visible: Boolean,
    onConfirm: (ReelsSetupConfig) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var includeVideoAttachments by remember { mutableStateOf(true) }
    var includeTikToks by remember { mutableStateOf(true) }
    var includeInstagrams by remember { mutableStateOf(true) }
    var downloadOnCellular by remember { mutableStateOf(true) }

    // At least one source must be enabled
    val hasAtLeastOneSource = includeVideoAttachments || includeTikToks || includeInstagrams
    // Social media sources enabled (for showing the warning)
    val hasSocialMediaSources = includeTikToks || includeInstagrams

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Subscriptions,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Enable Reels",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Browse videos full-screen with swipe gestures, like TikTok or Instagram.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Include in Reels:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Video sources
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // Video attachments toggle
                        SourceToggleRow(
                            title = "Video attachments",
                            subtitle = "Videos sent directly in chat",
                            checked = includeVideoAttachments,
                            onCheckedChange = { includeVideoAttachments = it }
                        )

                        // TikToks toggle
                        SourceToggleRow(
                            title = "TikToks",
                            subtitle = "TikTok links shared in chat",
                            checked = includeTikToks,
                            onCheckedChange = { includeTikToks = it }
                        )

                        // Instagrams toggle
                        SourceToggleRow(
                            title = "Instagram Reels",
                            subtitle = "Instagram video links shared in chat",
                            checked = includeInstagrams,
                            onCheckedChange = { includeInstagrams = it }
                        )
                    }
                }

                // Error message if no source selected
                if (!hasAtLeastOneSource) {
                    Text(
                        text = "Select at least one video source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Cellular download option
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Download via cellular",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (downloadOnCellular) {
                                    "Videos download on any network"
                                } else {
                                    "Videos only download on WiFi"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(
                            checked = downloadOnCellular,
                            onCheckedChange = { downloadOnCellular = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Warnings at the bottom (small text)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Storage warning
                    Text(
                        text = "Videos are cached locally and will consume storage space.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Social media warning (only show if TikTok or Instagram enabled)
                    if (hasSocialMediaSources) {
                        Text(
                            text = "TikTok and Instagram videos are fetched directly from their servers and may occasionally fail or be blocked.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ReelsSetupConfig(
                            includeVideoAttachments = includeVideoAttachments,
                            includeTikToks = includeTikToks,
                            includeInstagrams = includeInstagrams,
                            downloadOnCellular = downloadOnCellular
                        )
                    )
                },
                enabled = hasAtLeastOneSource
            ) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SourceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
