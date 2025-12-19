package com.bothbubbles.ui.chat.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.core.model.NoLocationReason
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Section showing Life360 member location on a map.
 * Shows when contact is linked to a Life360 member.
 * If location is unavailable, shows a placeholder with reason.
 */
@Composable
fun Life360LocationSection(
    life360Member: Life360Member,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val location = life360Member.location
    val hasLocation = location != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Icon(
                imageVector = if (hasLocation) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                contentDescription = null,
                tint = if (hasLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Location",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Map card or unavailable placeholder
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (location != null) {
                    Life360MapView(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        displayName = life360Member.displayName,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Transparent overlay to capture clicks (MapView consumes touch events)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onMapClick)
                    )
                } else {
                    LocationUnavailablePlaceholder()
                }
            }
        }

        // Location info
        Column(
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
        ) {
            if (location != null) {
                // Place name or address
                val locationText = location.placeName ?: location.address
                if (locationText != null) {
                    Text(
                        text = locationText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Timestamp
                val formattedTime = remember(location.timestamp) {
                    formatLocationTime(location.timestamp)
                }
                Text(
                    text = "Updated $formattedTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Show reason for unavailable location
                val reasonText = when (life360Member.noLocationReason) {
                    NoLocationReason.EXPLICIT -> "Location sharing disabled"
                    NoLocationReason.NOT_SHARING -> "Not sharing in this circle"
                    NoLocationReason.NO_REASON -> "May have lost connection"
                    NoLocationReason.NOT_FOUND -> "Member not found"
                    else -> "Location unavailable"
                }
                Text(
                    text = reasonText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Battery if available
            if (life360Member.battery != null) {
                val batteryText = buildString {
                    append("Battery: ${life360Member.battery}%")
                    if (life360Member.isCharging == true) append(" (charging)")
                }
                Text(
                    text = batteryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Placeholder shown when Life360 location is unavailable.
 * Shows a grid pattern with a question mark icon.
 */
@Composable
private fun LocationUnavailablePlaceholder() {
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val gridSize = 40.dp.toPx()
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                // Draw vertical lines
                var x = gridSize
                while (x < size.width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        pathEffect = pathEffect,
                        strokeWidth = 1f
                    )
                    x += gridSize
                }

                // Draw horizontal lines
                var y = gridSize
                while (y < size.height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        pathEffect = pathEffect,
                        strokeWidth = 1f
                    )
                    y += gridSize
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "?",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Unavailable",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Life360MapView(
    latitude: Double,
    longitude: Double,
    displayName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setMultiTouchControls(false)
                isFocusable = false
                isClickable = false

                val geoPoint = GeoPoint(latitude, longitude)
                controller.setZoom(16.0)
                controller.setCenter(geoPoint)

                // Add marker
                val marker = Marker(this).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = displayName
                }
                overlays.add(marker)
            }
        },
        modifier = modifier
    )
}

/**
 * Modal bottom sheet with Life360 and Navigate options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Life360LocationActionsSheet(
    life360Member: Life360Member,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val location = life360Member.location ?: return
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "${life360Member.displayName}'s Location",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Life360 option
            LocationActionItem(
                icon = Icons.Filled.LocationOn,
                label = "Open in Life360",
                subtitle = "View full details in Life360 app",
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.life360.android.safetymapd")
                    if (intent != null) {
                        context.startActivity(intent)
                    }
                    onDismiss()
                }
            )

            // Navigate option
            LocationActionItem(
                icon = Icons.Outlined.Navigation,
                label = "Navigate",
                subtitle = "Get directions to this location",
                onClick = {
                    val uri = Uri.parse("google.navigation:q=${location.latitude},${location.longitude}")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                    onDismiss()
                }
            )

            // Share location option
            LocationActionItem(
                icon = Icons.Outlined.Share,
                label = "Share Location",
                subtitle = location.address ?: "${location.latitude}, ${location.longitude}",
                onClick = {
                    val shareText = buildString {
                        append("${life360Member.displayName}'s location: ")
                        if (location.address != null) {
                            append(location.address)
                            append("\n")
                        }
                        append("https://maps.google.com/?q=${location.latitude},${location.longitude}")
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share location"))
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun LocationActionItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatLocationTime(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000} min ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        else -> {
            val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            formatter.format(Date(timestamp))
        }
    }
}
