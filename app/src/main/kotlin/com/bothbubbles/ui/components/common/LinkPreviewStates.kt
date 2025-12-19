package com.bothbubbles.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Link preview state components for loading, error, and minimal fallback states.
 */

/**
 * Loading state for link preview
 */
@Composable
fun LinkPreviewLoading(
    domain: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val cardColors = linkPreviewCardColors(LinkPreviewSurfaceLevel.Low)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Loading preview from $domain...",
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/**
 * Error/retry state for link preview
 */
@Composable
fun LinkPreviewError(
    url: String,
    domain: String,
    isFromMe: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardColors = linkPreviewCardColors(LinkPreviewSurfaceLevel.Low)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable {
                openUrl(context, url)
            },
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Placeholder image area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(placeholderColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = textColor.copy(alpha = 0.5f)
                )
            }

            // Domain and retry button
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry",
                        modifier = Modifier.size(16.dp),
                        tint = textColor
                    )
                }
            }
        }
    }
}

/**
 * Minimal link preview shown when no metadata is available
 */
@Composable
fun LinkPreviewMinimal(
    url: String,
    domain: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardColors = linkPreviewCardColors(LinkPreviewSurfaceLevel.Low)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable {
                openUrl(context, url)
            },
        colors = cardColors,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Placeholder image area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(placeholderColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = textColor.copy(alpha = 0.5f)
                )
            }

            // Domain
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor
                )
            }
        }
    }
}
