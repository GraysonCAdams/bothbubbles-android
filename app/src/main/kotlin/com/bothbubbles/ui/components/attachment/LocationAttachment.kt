package com.bothbubbles.ui.components.attachment

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bothbubbles.ui.components.message.AttachmentUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import timber.log.Timber
import java.io.File

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
                // Open in default maps app using geo: URI
                // This lets the user's preferred maps app handle it
                coords?.let { (lat, lng) ->
                    try {
                        val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Shared Location)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                        context.startActivity(mapIntent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open maps app")
                    }
                }
            },
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            when {
                // Case 1: We have coordinates - show OSMDroid map preview
                coords != null -> {
                    val (lat, lng) = coords
                    val isDarkTheme = isSystemInDarkTheme()

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // OSMDroid map preview (same as Life360)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            LocationMapView(
                                latitude = lat,
                                longitude = lng,
                                isDarkTheme = isDarkTheme,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Location info footer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left side: title and coordinates
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Shared Location",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = contentColor
                                )
                                Text(
                                    text = formatCoordinates(lat, lng),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryContentColor
                                )
                            }
                            // Round navigate button - vertically centered
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
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
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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
 * Formats coordinates for display.
 */
private fun formatCoordinates(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return "%.4f°$latDir, %.4f°$lngDir".format(kotlin.math.abs(lat), kotlin.math.abs(lng))
}

/**
 * Dark tile source using CartoDB Voyager (muted, softer dark mode alternative).
 */
private val DARK_TILE_SOURCE = XYTileSource(
    "CartoDB_Voyager",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    )
)

/**
 * OSMDroid map view for location preview.
 * Supports dark mode with CartoDB Dark Matter tiles.
 */
@Composable
private fun LocationMapView(
    latitude: Double,
    longitude: Double,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(if (isDarkTheme) DARK_TILE_SOURCE else TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setMultiTouchControls(false)
                isFocusable = false
                isClickable = false

                val geoPoint = GeoPoint(latitude, longitude)
                controller.setZoom(16.0)

                // Offset center slightly south so pin appears visually centered
                // Pin height is roughly 0.0003 degrees at zoom 16
                val centeredPoint = GeoPoint(latitude - 0.0003, longitude)
                controller.setCenter(centeredPoint)

                // Add red location marker
                val marker = Marker(this).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = createRedPinDrawable(ctx)
                }
                overlays.add(marker)
            }
        },
        modifier = modifier
    )
}

/**
 * Creates a red map pin drawable for the location marker.
 */
private fun createRedPinDrawable(context: android.content.Context): android.graphics.drawable.Drawable {
    val size = 72
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    val cx = size / 2f
    val pinRadius = size * 0.28f
    val pinCenterY = size * 0.32f

    // Shadow
    paint.color = 0x40000000
    canvas.drawCircle(cx + 2, pinCenterY + 2, pinRadius, paint)

    // Red pin body (circle)
    paint.color = 0xFFE53935.toInt() // MD3 Red 600
    paint.style = android.graphics.Paint.Style.FILL
    canvas.drawCircle(cx, pinCenterY, pinRadius, paint)

    // Pin point (triangle pointing down)
    val path = android.graphics.Path()
    path.moveTo(cx - pinRadius * 0.5f, pinCenterY + pinRadius * 0.7f)
    path.lineTo(cx, size * 0.85f)
    path.lineTo(cx + pinRadius * 0.5f, pinCenterY + pinRadius * 0.7f)
    path.close()
    canvas.drawPath(path, paint)

    // White inner circle
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, pinCenterY, pinRadius * 0.4f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}
