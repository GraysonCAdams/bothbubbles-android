package com.bothbubbles.ui.chat.composer.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import timber.log.Timber
import kotlin.math.abs

/**
 * Max width for the inline location embed card.
 */
private val LocationEmbedMaxWidth = 280.dp

/**
 * Inline location embed for the segmented composer.
 *
 * Displays a location preview as an inline card that can be:
 * - Removed via dismiss button
 * - Tapped to open in maps app
 *
 * Shows:
 * - Static map thumbnail from OpenStreetMap
 * - Location name or "Shared Location"
 * - Coordinates or address
 *
 * @param latitude Location latitude
 * @param longitude Location longitude
 * @param name Optional location name (e.g., "Coffee Shop")
 * @param address Optional address from reverse geocoding
 * @param onRemove Callback when dismiss button is tapped
 * @param modifier Modifier for the container
 */
@Composable
fun ComposerLocationEmbed(
    latitude: Double,
    longitude: Double,
    name: String?,
    address: String?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Build static map URL for preview
    val staticMapUrl = buildStaticMapUrl(latitude, longitude)

    Box(
        modifier = modifier
            .widthIn(max = LocationEmbedMaxWidth)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable {
                    // Open in default maps app
                    openInMapsApp(context, latitude, longitude, name)
                }
        ) {
            // Static map image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                // Load static map from OpenStreetMap
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(staticMapUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Location preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Location pin overlay (centered)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Location info footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: name and coordinates
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Location name or "Shared Location"
                    Text(
                        text = name ?: "Shared Location",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Address or formatted coordinates
                    Text(
                        text = address ?: formatCoordinates(latitude, longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Navigate button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = "Navigate",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Dismiss button (top right, always visible)
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove location",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Builds a static map image URL using OpenStreetMap.
 * Centers the map slightly north so the marker appears centered.
 */
private fun buildStaticMapUrl(lat: Double, lng: Double): String {
    // Offset center north so pin appears vertically centered in the preview
    val centeredLat = lat + 0.0008
    return "https://staticmap.openstreetmap.de/staticmap.php?" +
        "center=$centeredLat,$lng&zoom=15&size=400x200&markers=$lat,$lng,red-pushpin"
}

/**
 * Formats coordinates for display.
 */
private fun formatCoordinates(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return "%.4f°$latDir, %.4f°$lngDir".format(abs(lat), abs(lng))
}

/**
 * Opens the location in the device's default maps app.
 */
private fun openInMapsApp(
    context: android.content.Context,
    lat: Double,
    lng: Double,
    name: String?
) {
    try {
        val label = name ?: "Shared Location"
        val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
        context.startActivity(mapIntent)
    } catch (e: Exception) {
        Timber.e(e, "Failed to open maps app")
    }
}
