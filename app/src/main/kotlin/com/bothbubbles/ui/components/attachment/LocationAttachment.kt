package com.bothbubbles.ui.components.attachment

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.ui.components.message.AttachmentUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.URLEncoder

/**
 * Renders an Apple vLocation attachment as a map preview.
 *
 * vLocation files contain a VCF with an Apple Maps URL like:
 * https://maps.apple.com/?ll=LAT,LNG&q=LAT,LNG
 *
 * We parse the coordinates and display:
 * - Static map image from OpenStreetMap
 * - "Shared Location" title
 * - Coordinates subtitle
 *
 * Tapping opens the location in the device's default maps app.
 *
 * For inbound locations that haven't been downloaded yet, shows a placeholder
 * with download button or progress indicator.
 */
@Composable
fun LocationAttachment(
    attachment: AttachmentUiModel,
    interactions: AttachmentInteractions,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    onDownloadClick: (() -> Unit)? = null,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f
) {
    val context = LocalContext.current

    // Parse coordinates from the vLocation file
    var coordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var parseError by remember { mutableStateOf(false) }

    // Track whether we need to download
    val needsDownload = attachment.localPath == null && !isFromMe

    LaunchedEffect(attachment.localPath) {
        Timber.d("[LOCATION_DEBUG] LocationAttachment: localPath=${attachment.localPath}, needsDownload=$needsDownload, isDownloading=$isDownloading")
        attachment.localPath?.let { path ->
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val content = file.readText()
                        Timber.d("[LOCATION_DEBUG] VCF content: $content")
                        coordinates = parseVLocationCoordinates(content)
                        parseError = coordinates == null
                        Timber.d("[LOCATION_DEBUG] Parsed coordinates: $coordinates, parseError=$parseError")
                    } else {
                        Timber.w("[LOCATION_DEBUG] File does not exist: $path")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[LOCATION_DEBUG] Error parsing vLocation")
                    parseError = true
                }
            }
        }
    }

    val coords = coordinates

    // Material3 theming - use proper container colors that work in both light and dark mode
    val containerColor = if (isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = if (isFromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val secondaryContentColor = if (isFromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = coords != null) {
                // Open in maps app
                coords?.let { (lat, lng) ->
                    val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                    if (mapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mapIntent)
                    } else {
                        // Fallback to Google Maps web
                        val webUri = Uri.parse("https://maps.google.com/?q=$lat,$lng")
                        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                    }
                }
            },
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            when {
                // Case 1: We have coordinates - show the map
                coords != null -> {
                    val (lat, lng) = coords
                    val mapUrl = buildStaticMapUrl(lat, lng)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(mapUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Location map",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        )

                        // Location pin overlay
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                        )
                    }

                    // Location info
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Shared Location",
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatCoordinates(lat, lng),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Case 2: Needs download - show download state
                needsDownload -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(48.dp)
                            )

                            if (isDownloading) {
                                // Show download progress
                                CircularProgressIndicator(
                                    progress = { downloadProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = contentColor
                                )
                                Text(
                                    text = "Loading location...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryContentColor
                                )
                            } else {
                                // Show download button
                                FilledTonalButton(
                                    onClick = { onDownloadClick?.invoke() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Load Location",
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Case 3: Parse error
                parseError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = secondaryContentColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Unable to load location",
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryContentColor
                        )
                    }
                }

                // Case 4: Loading (parsing file)
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parses coordinates from a vLocation VCF content.
 *
 * Looks for URL line like:
 * URL;type=pref:https://maps.apple.com/?ll=LAT\,LNG&q=LAT\,LNG
 */
private fun parseVLocationCoordinates(vcfContent: String): Pair<Double, Double>? {
    // Find the URL line
    val lines = vcfContent.split("\n")
    val urlLine = lines.firstOrNull { it.contains("URL") } ?: return null

    // Extract the URL part
    val url = urlLine.substringAfter("pref:").replace("\\,", ",")

    // Parse ll= parameter
    val llMatch = Regex("""ll=(-?\d+\.?\d*),(-?\d+\.?\d*)""").find(url)
    if (llMatch != null) {
        val lat = llMatch.groupValues[1].toDoubleOrNull()
        val lng = llMatch.groupValues[2].toDoubleOrNull()
        if (lat != null && lng != null) {
            return Pair(lat, lng)
        }
    }

    return null
}

/**
 * Builds a static map URL from OpenStreetMap.
 */
private fun buildStaticMapUrl(lat: Double, lng: Double): String {
    // Use OpenStreetMap static map service (no API key required)
    val zoom = 15
    val width = 400
    val height = 200
    val marker = URLEncoder.encode("$lat,$lng", "UTF-8")
    return "https://staticmap.openstreetmap.de/staticmap.php" +
            "?center=$lat,$lng" +
            "&zoom=$zoom" +
            "&size=${width}x${height}" +
            "&markers=$lat,$lng,red-pushpin"
}

/**
 * Formats coordinates for display.
 */
private fun formatCoordinates(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return "%.4f°$latDir, %.4f°$lngDir".format(kotlin.math.abs(lat), kotlin.math.abs(lng))
}
