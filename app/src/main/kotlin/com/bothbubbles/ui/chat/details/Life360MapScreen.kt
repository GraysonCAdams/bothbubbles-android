package com.bothbubbles.ui.chat.details

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.util.AvatarGenerator
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import timber.log.Timber
import java.net.URL
import kotlin.math.roundToInt

/**
 * Full-screen map showing contact's Life360 location with user's current position.
 * Bottom bar shows distance/time and navigation options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Life360MapScreen(
    onBack: () -> Unit,
    viewModel: Life360MapViewModel = hiltViewModel()
) {
    val life360Member by viewModel.life360Member.collectAsStateWithLifecycle()
    val avatarPath by viewModel.avatarPath.collectAsStateWithLifecycle()

    life360Member?.let { member ->
        Life360MapContent(
            life360Member = member,
            avatarPath = avatarPath,
            onBack = onBack
        )
    } ?: run {
        // Loading or member not found
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Life360MapContent(
    life360Member: Life360Member,
    avatarPath: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location = life360Member.location

    // User's current location
    var userLat by remember { mutableDoubleStateOf(0.0) }
    var userLon by remember { mutableDoubleStateOf(0.0) }
    var hasUserLocation by remember { mutableStateOf(false) }

    // Distance and time from OSRM
    var distanceMiles by remember { mutableStateOf<Double?>(null) }
    var durationMinutes by remember { mutableStateOf<Int?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // Pulse animation frame for user location marker
    var pulseFrame by remember { mutableIntStateOf(0) }

    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    // Get user's location
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                val cancellationTokenSource = CancellationTokenSource()

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).addOnSuccessListener { loc ->
                    if (loc != null) {
                        userLat = loc.latitude
                        userLon = loc.longitude
                        hasUserLocation = true

                        // Fetch route info
                        if (location != null) {
                            scope.launch {
                                isLoadingRoute = true
                                val routeInfo = fetchRouteInfo(
                                    userLat, userLon,
                                    location.latitude, location.longitude
                                )
                                distanceMiles = routeInfo?.first
                                durationMinutes = routeInfo?.second
                                isLoadingRoute = false
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Timber.w(e, "Location permission denied")
            }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Pulse animation for user location
    LaunchedEffect(hasUserLocation) {
        if (hasUserLocation) {
            while (true) {
                delay(50)
                pulseFrame = (pulseFrame + 1) % 60 // 60 frames = ~3 second cycle
            }
        }
    }

    // MD3 colors for markers
    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = life360Member.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        location?.let {
                            Text(
                                text = it.placeName ?: it.address ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        },
        bottomBar = {
            if (location != null) {
                Life360MapBottomBar(
                    distanceMiles = distanceMiles,
                    durationMinutes = durationMinutes,
                    isLoading = isLoadingRoute,
                    onOpenLife360 = {
                        val intent = context.packageManager.getLaunchIntentForPackage("com.life360.android.safetymapd")
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    },
                    onNavigate = {
                        val uri = Uri.parse("google.navigation:q=${location.latitude},${location.longitude}")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    }
                )
            }
        }
    ) { paddingValues ->
        if (location == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Location unavailable",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Life360FullMapView(
                    targetLat = location.latitude,
                    targetLon = location.longitude,
                    targetName = life360Member.displayName,
                    avatarPath = avatarPath,
                    userLat = userLat,
                    userLon = userLon,
                    hasUserLocation = hasUserLocation,
                    pulseFrame = pulseFrame,
                    primaryColor = primaryColor
                )

                // Status overlay in top right corner
                Life360StatusOverlay(
                    battery = life360Member.battery,
                    isCharging = life360Member.isCharging,
                    isDriving = location.isDriving,
                    locationTimestamp = location.timestamp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )
            }
        }
    }
}

/**
 * Status overlay showing battery, activity, and last updated time.
 * Displayed in the top right corner of the full-screen map.
 */
@Composable
private fun Life360StatusOverlay(
    battery: Int?,
    isCharging: Boolean?,
    isDriving: Boolean?,
    locationTimestamp: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Battery (if available)
            battery?.let { batteryLevel ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when {
                            batteryLevel <= 20 -> MaterialTheme.colorScheme.error
                            batteryLevel <= 50 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        text = buildString {
                            append("$batteryLevel%")
                            if (isCharging == true) append(" ⚡")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Activity (Driving)
            if (isDriving == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Driving",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Last updated
            val formattedTime = remember(locationTimestamp) {
                formatLocationTime(locationTimestamp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Life360MapBottomBar(
    distanceMiles: Double?,
    durationMinutes: Int?,
    isLoading: Boolean,
    onOpenLife360: () -> Unit,
    onNavigate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Open Life360 button
            OutlinedButton(
                onClick = onOpenLife360,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Life360")
            }

            // Distance and time
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else if (distanceMiles != null && durationMinutes != null) {
                    Text(
                        text = formatDuration(durationMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatDistance(distanceMiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Navigate button
            Button(
                onClick = onNavigate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Outlined.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Navigate")
            }
        }
    }
}

@Composable
private fun Life360FullMapView(
    targetLat: Double,
    targetLon: Double,
    targetName: String,
    avatarPath: String?,
    userLat: Double,
    userLon: Double,
    hasUserLocation: Boolean,
    pulseFrame: Int,
    primaryColor: Color
) {
    val context = LocalContext.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var hasZoomedToBounds by remember { mutableStateOf(false) }

    // Create avatar bitmap once
    val avatarBitmap = remember(avatarPath, targetName) {
        createAvatarPinDrawable(context, targetName, avatarPath, primaryColor.toArgb())
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                setMultiTouchControls(true)

                val targetPoint = GeoPoint(targetLat, targetLon)
                controller.setZoom(15.0)
                controller.setCenter(targetPoint)

                // Add target marker with avatar pin
                val targetMarker = Marker(this).apply {
                    position = targetPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = targetName
                    icon = avatarBitmap
                }
                overlays.add(targetMarker)

                mapView = this
            }
        },
        update = { view ->
            // Update user location marker when available
            if (hasUserLocation) {
                // Remove old user marker if exists
                view.overlays.removeAll { overlay ->
                    (overlay as? Marker)?.id == "user_location"
                }

                // Calculate pulse scale (0.8 to 1.2) from frame
                val pulseProgress = pulseFrame / 60f
                val pulseScale = 0.8f + 0.4f * kotlin.math.sin(pulseProgress * 2 * Math.PI).toFloat()

                val userMarker = Marker(view).apply {
                    id = "user_location"
                    position = GeoPoint(userLat, userLon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You"
                    icon = createPulsingBlueCircleDrawable(context, pulseScale)
                }
                view.overlays.add(userMarker)

                // Zoom to show both markers (only once)
                if (!hasZoomedToBounds) {
                    val targetPoint = GeoPoint(targetLat, targetLon)
                    val userPoint = GeoPoint(userLat, userLon)
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(listOf(targetPoint, userPoint))
                    view.zoomToBoundingBox(boundingBox.increaseByScale(1.5f), true)
                    hasZoomedToBounds = true
                }

                view.invalidate()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Create an avatar-based map pin marker.
 * Shows the contact's photo in a teardrop-shaped pin.
 */
private fun createAvatarPinDrawable(
    context: Context,
    name: String,
    avatarPath: String?,
    accentColor: Int
): BitmapDrawable {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val avatarCenterY = size * 0.35f
    val avatarRadius = size * 0.32f

    // Shadow
    paint.color = 0x40000000
    canvas.drawCircle(cx, avatarCenterY + 6, avatarRadius + 4, paint)

    // White border/background for avatar
    paint.color = 0xFFFFFFFF.toInt()
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, avatarCenterY, avatarRadius + 4, paint)

    // Draw the pointed bottom (teardrop tail)
    val path = android.graphics.Path()
    path.moveTo(cx - avatarRadius * 0.5f, avatarCenterY + avatarRadius * 0.7f)
    path.quadTo(cx, size * 0.92f, cx + avatarRadius * 0.5f, avatarCenterY + avatarRadius * 0.7f)
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

    // Create circular clip for avatar
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
    paint.strokeWidth = 3f
    canvas.drawCircle(cx, avatarCenterY, avatarRadius + 2, paint)

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * Create a pulsing blue circle for user's current location.
 * @param pulseScale Scale factor for the outer ring (0.8 to 1.2 for pulsing effect)
 */
private fun createPulsingBlueCircleDrawable(context: Context, pulseScale: Float): BitmapDrawable {
    val baseSize = 64
    val size = (baseSize * 1.3f).toInt() // Extra space for pulse
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    val blueColor = 0xFF2196F3.toInt() // Material Blue 500

    // Outer pulsing ring (fades based on scale)
    val outerRadius = baseSize / 2f * pulseScale
    val outerAlpha = ((1f - (pulseScale - 0.8f) / 0.4f) * 80).toInt().coerceIn(0, 80)
    paint.color = blueColor
    paint.alpha = outerAlpha
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, outerRadius, paint)

    // Middle ring
    paint.alpha = 100
    canvas.drawCircle(cx, cy, baseSize / 3.5f, paint)

    // Inner filled circle
    paint.alpha = 255
    canvas.drawCircle(cx, cy, baseSize / 5f, paint)

    // White center dot
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cy, baseSize / 10f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * Fetch route info (distance and duration) from OSRM.
 */
private suspend fun fetchRouteInfo(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double
): Pair<Double, Int>? = withContext(Dispatchers.IO) {
    try {
        // OSRM public API (free, no key required)
        val url = URL("https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=false")
        val response = url.readText()
        val json = JSONObject(response)

        if (json.getString("code") == "Ok") {
            val routes = json.getJSONArray("routes")
            if (routes.length() > 0) {
                val route = routes.getJSONObject(0)
                val distanceMeters = route.getDouble("distance")
                val durationSeconds = route.getDouble("duration")

                val distanceMiles = distanceMeters * 0.000621371
                val durationMinutes = (durationSeconds / 60).roundToInt()

                return@withContext Pair(distanceMiles, durationMinutes)
            }
        }
        null
    } catch (e: Exception) {
        Timber.w(e, "Failed to fetch route info from OSRM")
        null
    }
}

private fun formatDistance(miles: Double): String {
    return if (miles < 0.1) {
        "${(miles * 5280).roundToInt()} ft"
    } else {
        String.format("%.1f mi", miles)
    }
}

private fun formatDuration(minutes: Int): String {
    return when {
        minutes < 1 -> "< 1 min"
        minutes < 60 -> "$minutes min"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
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
            val formatter = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            formatter.format(java.util.Date(timestamp))
        }
    }
}
