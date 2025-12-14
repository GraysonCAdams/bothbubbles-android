package com.bothbubbles.ui.settings.export

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.export.ExportFormat
import com.bothbubbles.services.export.ExportStyle
import com.bothbubbles.ui.settings.components.SettingsCard

/**
 * Format selection card for export (HTML/PDF)
 */
@Composable
fun ExportFormatSelection(
    selectedFormat: ExportFormat,
    onFormatSelected: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Format",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = selectedFormat == ExportFormat.HTML,
                    onClick = { onFormatSelected(ExportFormat.HTML) },
                    label = { Text("HTML") },
                    leadingIcon = if (selectedFormat == ExportFormat.HTML) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedFormat == ExportFormat.PDF,
                    onClick = { onFormatSelected(ExportFormat.PDF) },
                    label = { Text("PDF") },
                    leadingIcon = if (selectedFormat == ExportFormat.PDF) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Style selection card for export (Chat Bubbles/Plain Text)
 */
@Composable
fun ExportStyleSelection(
    selectedStyle: ExportStyle,
    onStyleSelected: (ExportStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Style",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = selectedStyle == ExportStyle.CHAT_BUBBLES,
                    onClick = { onStyleSelected(ExportStyle.CHAT_BUBBLES) },
                    label = { Text("Chat Bubbles") },
                    leadingIcon = if (selectedStyle == ExportStyle.CHAT_BUBBLES) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedStyle == ExportStyle.PLAIN_TEXT,
                    onClick = { onStyleSelected(ExportStyle.PLAIN_TEXT) },
                    label = { Text("Plain Text") },
                    leadingIcon = if (selectedStyle == ExportStyle.PLAIN_TEXT) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
