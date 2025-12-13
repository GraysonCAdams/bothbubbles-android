package com.bothbubbles.ui.components.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Utility functions for link preview components.
 * Includes URL handling, clipboard operations, and color theming.
 */

/**
 * Opens a URL in the default browser
 */
fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        // Ignore if no browser available
    }
}

/**
 * Copies URL to clipboard and shows a toast
 */
fun copyUrlToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("URL", url)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

/**
 * Extracts the domain from a URL.
 * Returns the domain without protocol and path.
 */
fun extractDomain(url: String): String {
    return try {
        val uri = Uri.parse(url)
        uri.host ?: url
    } catch (e: Exception) {
        url
    }
}

/**
 * Returns theme-aware card colors for link previews
 */
@Composable
fun linkPreviewCardColors(surfaceLevel: LinkPreviewSurfaceLevel = LinkPreviewSurfaceLevel.High) =
    CardDefaults.cardColors(
        containerColor = when (surfaceLevel) {
            LinkPreviewSurfaceLevel.Highest -> MaterialTheme.colorScheme.surfaceContainerHighest
            LinkPreviewSurfaceLevel.High -> MaterialTheme.colorScheme.surfaceContainerHigh
            LinkPreviewSurfaceLevel.Low -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    )

/**
 * Surface elevation level for link preview cards
 */
enum class LinkPreviewSurfaceLevel {
    Highest,
    High,
    Low
}

/**
 * Returns the play button overlay color for video previews
 */
val videoOverlayColor: Color
    get() = Color.Black.copy(alpha = 0.6f)
