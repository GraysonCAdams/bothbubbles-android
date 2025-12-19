package com.bothbubbles.ui.chat.details

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.core.model.NoLocationReason
import com.bothbubbles.util.AvatarGenerator
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
 * Location is considered stale after 30 minutes.
 * After this threshold, we show "Location unavailable" instead of potentially outdated data.
 */
private const val LOCATION_STALE_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

/**
 * Section showing Life360 member location on a map.
 * Shows when contact is linked to a Life360 member.
 * If location is unavailable or stale (>30 min old), shows a placeholder.
 *
 * @param life360Member The Life360 member data
 * @param avatarPath Path to the contact's avatar image
 * @param isRefreshing Whether a location refresh is in progress
 * @param onMapClick Called when the map is tapped
 * @param onRefreshClick Called when the refresh icon is tapped
 */

@Composable
fun Life360LocationSection(
    life360Member: Life360Member,
    avatarPath: String?,
    isRefreshing: Boolean,
    onMapClick: () -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val location = life360Member.location
    val now = System.currentTimeMillis()

    // Location is unavailable if:
    // 1. No location data at all, OR
    // 2. Location is older than 30 minutes (stale/outdated)
    val isLocationStale = location != null &&
        (now - location.timestamp) > LOCATION_STALE_THRESHOLD_MS
    val hasValidLocation = location != null && !isLocationStale
    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header row (matches MediaSection style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onMapClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasValidLocation) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                    contentDescription = null,
                    tint = if (hasValidLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Location",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Map preview
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (hasValidLocation && location != null) {
                        Life360MapView(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            displayName = life360Member.displayName,
                            avatarPath = avatarPath,
                            accentColor = primaryColor.toArgb(),
                            modifier = Modifier.fillMaxSize()
                        )
                        // Transparent overlay to capture clicks (MapView consumes touch events)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onMapClick)
                        )
                    } else {
                        LocationUnavailablePlaceholder(isStale = isLocationStale)
                    }
                }
            }

            // Location info
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (hasValidLocation && location != null) {
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

                    // Timestamp with optional refresh icon
                    val formattedTime = remember(location.timestamp) {
                        formatLocationTime(location.timestamp)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Always show refresh icon (location can always be refreshed)
                        val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "refresh_rotation"
                        )

                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh location",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .then(
                                    if (isRefreshing) Modifier.rotate(rotation) else Modifier
                                )
                                .clickable(enabled = !isRefreshing, onClick = onRefreshClick)
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                            text = "Updated $formattedTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Show reason for unavailable location
                    val reasonText = when {
                        isLocationStale -> "Location outdated (30+ min ago)"
                        life360Member.noLocationReason == NoLocationReason.EXPLICIT -> "Location sharing disabled"
                        life360Member.noLocationReason == NoLocationReason.NOT_SHARING -> "Not sharing in this circle"
                        life360Member.noLocationReason == NoLocationReason.NO_REASON -> "May have lost connection"
                        life360Member.noLocationReason == NoLocationReason.NOT_FOUND -> "Member not found"
                        else -> "Location unavailable"
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Show refresh icon for stale/unavailable locations
                        val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "refresh_rotation"
                        )

                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh location",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .then(
                                    if (isRefreshing) Modifier.rotate(rotation) else Modifier
                                )
                                .clickable(enabled = !isRefreshing, onClick = onRefreshClick)
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Battery if available (only show when location is valid, not when outdated)
                if (life360Member.battery != null && hasValidLocation) {
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
}

/**
 * Placeholder shown when Life360 location is unavailable or stale.
 * Shows a grid pattern with an icon indicating the reason.
 *
 * @param isStale True if location exists but is older than 30 minutes
 */
@Composable
private fun LocationUnavailablePlaceholder(isStale: Boolean = false) {
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
                    Icon(
                        imageVector = Icons.Filled.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isStale) "Outdated" else "Unavailable",
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
    avatarPath: String?,
    accentColor: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Create avatar pin bitmap
    val avatarPinDrawable = remember(avatarPath, displayName) {
        createPreviewAvatarPinDrawable(context, displayName, avatarPath, accentColor)
    }

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

                // Add marker with avatar pin
                val marker = Marker(this).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = displayName
                    icon = avatarPinDrawable
                }
                overlays.add(marker)
            }
        },
        modifier = modifier
    )
}

/**
 * Create an avatar pin for the preview map.
 * Size is 108px (50% larger than original 72px) for better visibility.
 */
private fun createPreviewAvatarPinDrawable(
    context: Context,
    name: String,
    avatarPath: String?,
    accentColor: Int
): BitmapDrawable {
    val size = 108
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val avatarCenterY = size * 0.35f
    val avatarRadius = size * 0.30f

    // Shadow
    paint.color = 0x40000000
    canvas.drawCircle(cx, avatarCenterY + 4, avatarRadius + 3, paint)

    // White border/background for avatar
    paint.color = 0xFFFFFFFF.toInt()
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, avatarCenterY, avatarRadius + 3, paint)

    // Draw the pointed bottom (teardrop tail)
    val path = android.graphics.Path()
    path.moveTo(cx - avatarRadius * 0.5f, avatarCenterY + avatarRadius * 0.7f)
    path.quadTo(cx, size * 0.90f, cx + avatarRadius * 0.5f, avatarCenterY + avatarRadius * 0.7f)
    path.close()
    canvas.drawPath(path, paint)

    // Draw avatar image
    val avatarBitmap = if (avatarPath != null) {
        AvatarGenerator.loadContactPhotoBitmap(context, avatarPath, (avatarRadius * 2).toInt())
    } else {
        null
    } ?: AvatarGenerator.generateBitmap(context, name, (avatarRadius * 2).toInt())

    // Clip avatar to circle
    val avatarLeft = cx - avatarRadius
    val avatarTop = avatarCenterY - avatarRadius

    canvas.save()
    val clipPath = android.graphics.Path()
    clipPath.addCircle(cx, avatarCenterY, avatarRadius, android.graphics.Path.Direction.CW)
    canvas.clipPath(clipPath)
    canvas.drawBitmap(
        avatarBitmap,
        null,
        android.graphics.RectF(avatarLeft, avatarTop, avatarLeft + avatarRadius * 2, avatarTop + avatarRadius * 2),
        paint
    )
    canvas.restore()

    // Accent color ring around avatar
    paint.color = accentColor
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    canvas.drawCircle(cx, avatarCenterY, avatarRadius + 1.5f, paint)

    return BitmapDrawable(context.resources, bitmap)
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
                    openLife360App(context)
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

private const val LIFE360_PACKAGE = "com.life360.android.safetymapd"

/**
 * Opens the Life360 app, with fallback to Play Store if not installed.
 */
private fun openLife360App(context: Context) {
    // Try launching the app directly with ACTION_VIEW for better intent resolution
    val launchIntent = Intent(Intent.ACTION_VIEW).apply {
        `package` = LIFE360_PACKAGE
        data = Uri.parse("life360://")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(launchIntent)
        return
    } catch (_: Exception) {
        // Deep link didn't work, try package launch
    }

    // Try standard package launch
    val packageIntent = context.packageManager.getLaunchIntentForPackage(LIFE360_PACKAGE)
    if (packageIntent != null) {
        context.startActivity(packageIntent)
        return
    }

    // App not installed, open Play Store
    try {
        val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LIFE360_PACKAGE"))
        context.startActivity(playStoreIntent)
    } catch (_: Exception) {
        // Play Store not available, open web
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$LIFE360_PACKAGE"))
        context.startActivity(webIntent)
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

// ============================================================================
// GROUP CHAT LOCATIONS SECTION
// ============================================================================

/**
 * Data class to pair a Life360 member with their avatar path for display.
 */
data class Life360MemberWithAvatar(
    val member: Life360Member,
    val avatarPath: String?
)

/**
 * Section showing Life360 locations for multiple members in a group chat.
 * Shows a multi-pin map preview and a list of members with their location info.
 *
 * @param members List of Life360 members with their avatar paths
 * @param isRefreshing Whether a location refresh is in progress
 * @param onMapClick Called when the map is tapped (navigate to full map)
 * @param onMemberRefreshClick Called when a specific member's refresh is tapped
 */
@Composable
fun Life360LocationsSection(
    members: List<Life360MemberWithAvatar>,
    isRefreshing: Boolean,
    onMapClick: () -> Unit,
    onMemberRefreshClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val now = System.currentTimeMillis()

    // Filter members with valid (non-stale) locations for the map
    val membersWithValidLocations = members.filter { (member, _) ->
        val location = member.location
        location != null && (now - location.timestamp) <= LOCATION_STALE_THRESHOLD_MS
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onMapClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (membersWithValidLocations.isNotEmpty())
                        Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                    contentDescription = null,
                    tint = if (membersWithValidLocations.isNotEmpty())
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Locations",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${membersWithValidLocations.size} of ${members.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Multi-pin map preview
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (membersWithValidLocations.isNotEmpty()) {
                        Life360MultiPinMapView(
                            members = membersWithValidLocations,
                            accentColor = primaryColor.toArgb(),
                            modifier = Modifier.fillMaxSize()
                        )
                        // Transparent overlay to capture clicks
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onMapClick)
                        )
                    } else {
                        LocationUnavailablePlaceholder(isStale = false)
                    }
                }
            }

            // Member list with location info
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                members.forEach { (member, _) ->
                    Life360MemberLocationRow(
                        member = member,
                        isRefreshing = isRefreshing,
                        onRefreshClick = { onMemberRefreshClick(member.memberId) }
                    )
                }
            }
        }
    }
}

/**
 * Single row showing a Life360 member's location info in a group list.
 */
@Composable
private fun Life360MemberLocationRow(
    member: Life360Member,
    isRefreshing: Boolean,
    onRefreshClick: () -> Unit
) {
    val location = member.location
    val now = System.currentTimeMillis()
    val isLocationStale = location != null &&
        (now - location.timestamp) > LOCATION_STALE_THRESHOLD_MS
    val hasValidLocation = location != null && !isLocationStale

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Member name
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Location info or unavailable reason
        if (hasValidLocation && location != null) {
            val formattedTime = remember(location.timestamp) {
                formatLocationTime(location.timestamp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RefreshIcon(
                    isRefreshing = isRefreshing,
                    onClick = onRefreshClick
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val statusText = when {
                isLocationStale -> "Outdated"
                member.noLocationReason == NoLocationReason.EXPLICIT -> "Disabled"
                member.noLocationReason == NoLocationReason.NOT_SHARING -> "Not sharing"
                else -> "Unavailable"
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RefreshIcon(
                    isRefreshing = isRefreshing,
                    onClick = onRefreshClick
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Animated refresh icon used in location sections.
 */
@Composable
private fun RefreshIcon(
    isRefreshing: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_rotation"
    )

    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = "Refresh location",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(16.dp)
            .then(
                if (isRefreshing) Modifier.rotate(rotation) else Modifier
            )
            .clickable(enabled = !isRefreshing, onClick = onClick)
    )
}

/**
 * Map view with multiple avatar pins for group chat locations.
 */
@Composable
private fun Life360MultiPinMapView(
    members: List<Life360MemberWithAvatar>,
    accentColor: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Create avatar pin bitmaps for each member
    val avatarPins = remember(members) {
        members.mapNotNull { (member, avatarPath) ->
            val location = member.location ?: return@mapNotNull null
            Triple(
                member,
                GeoPoint(location.latitude, location.longitude),
                createPreviewAvatarPinDrawable(context, member.displayName, avatarPath, accentColor)
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setMultiTouchControls(false)
                isFocusable = false
                isClickable = false

                // Calculate bounding box for all pins
                if (avatarPins.isNotEmpty()) {
                    val latitudes = avatarPins.map { it.second.latitude }
                    val longitudes = avatarPins.map { it.second.longitude }

                    val centerLat = (latitudes.minOrNull()!! + latitudes.maxOrNull()!!) / 2
                    val centerLon = (longitudes.minOrNull()!! + longitudes.maxOrNull()!!) / 2

                    // Calculate appropriate zoom level based on spread
                    val latSpread = latitudes.maxOrNull()!! - latitudes.minOrNull()!!
                    val lonSpread = longitudes.maxOrNull()!! - longitudes.minOrNull()!!
                    val maxSpread = maxOf(latSpread, lonSpread)

                    val zoomLevel = when {
                        maxSpread < 0.001 -> 17.0  // Very close (same building)
                        maxSpread < 0.01 -> 15.0   // Same neighborhood
                        maxSpread < 0.1 -> 13.0    // Same city area
                        maxSpread < 1.0 -> 10.0    // Same city
                        else -> 8.0                 // Different cities
                    }

                    controller.setZoom(zoomLevel)
                    controller.setCenter(GeoPoint(centerLat, centerLon))

                    // Add markers for each member
                    avatarPins.forEach { (member, geoPoint, drawable) ->
                        val marker = Marker(this).apply {
                            position = geoPoint
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = member.displayName
                            icon = drawable
                        }
                        overlays.add(marker)
                    }
                }
            }
        },
        modifier = modifier
    )
}
