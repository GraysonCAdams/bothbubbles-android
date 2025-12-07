package com.bluebubbles.ui.components

import android.Manifest
import android.content.Context
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bluebubbles.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.*

/**
 * Attachment picker option data class
 */
data class AttachmentOption(
    val id: String,
    val icon: ImageVector,
    val labelResId: Int,
    val backgroundColor: Color
)

/**
 * Slide-up attachment picker panel matching the Google Messages style.
 * Provides quick access to Gallery, Camera, GIFs, Stickers, Files, Location, Contacts, and Schedule.
 */
@Composable
fun AttachmentPickerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAttachmentSelected: (Uri) -> Unit,
    onLocationSelected: (Double, Double) -> Unit,
    onContactSelected: (Uri) -> Unit, // contact URI for vCard generation
    onScheduleClick: () -> Unit,
    onMagicComposeClick: () -> Unit,
    onCameraClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            getCurrentLocation(context) { lat, lng ->
                onLocationSelected(lat, lng)
                onDismiss()
            }
        } else {
            Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery picker - supports multiple images/videos
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            onAttachmentSelected(uri)
        }
        if (uris.isNotEmpty()) onDismiss()
    }

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            onAttachmentSelected(uri)
        }
        if (uris.isNotEmpty()) onDismiss()
    }

    // Contact picker - returns URI for vCard generation
    val contactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        uri?.let {
            onContactSelected(it)
            onDismiss()
        }
    }

    // GIF picker using system intent
    val gifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onAttachmentSelected(it)
            onDismiss()
        }
    }

    // Define picker options
    val pickerOptions = remember {
        listOf(
            AttachmentOption("gallery", Icons.Outlined.Image, R.string.picker_gallery, Color(0xFF673AB7)),
            AttachmentOption("camera", Icons.Outlined.CameraAlt, R.string.picker_camera, Color(0xFF2196F3)),
            AttachmentOption("gif", Icons.Outlined.Gif, R.string.picker_gifs, Color(0xFFE91E63)),
            AttachmentOption("stickers", Icons.Outlined.EmojiEmotions, R.string.picker_stickers, Color(0xFFFF9800)),
            AttachmentOption("magic", Icons.Outlined.AutoAwesome, R.string.picker_magic_compose, Color(0xFF9C27B0)),
            AttachmentOption("files", Icons.Outlined.AttachFile, R.string.picker_files, Color(0xFF4CAF50)),
            AttachmentOption("location", Icons.Outlined.LocationOn, R.string.picker_location, Color(0xFFF44336)),
            AttachmentOption("contacts", Icons.Outlined.Person, R.string.picker_contacts, Color(0xFF00BCD4))
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Grid of attachment options
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pickerOptions) { option ->
                        AttachmentOptionItem(
                            option = option,
                            onClick = {
                                when (option.id) {
                                    "gallery" -> galleryLauncher.launch("image/*")
                                    "camera" -> {
                                        onCameraClick()
                                        onDismiss()
                                    }
                                    "gif" -> gifLauncher.launch("image/gif")
                                    "stickers" -> {
                                        // Launch sticker keyboard or fallback to emoji
                                        Toast.makeText(context, "Select stickers from keyboard", Toast.LENGTH_SHORT).show()
                                    }
                                    "magic" -> {
                                        onMagicComposeClick()
                                        onDismiss()
                                    }
                                    "files" -> fileLauncher.launch("*/*")
                                    "location" -> {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                    "contacts" -> contactLauncher.launch(null)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Schedule button at bottom
                AttachmentOptionItem(
                    option = AttachmentOption(
                        "schedule",
                        Icons.Outlined.Schedule,
                        R.string.picker_schedule,
                        Color(0xFF607D8B)
                    ),
                    onClick = {
                        onScheduleClick()
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Individual attachment option item with icon and label
 */
@Composable
private fun AttachmentOptionItem(
    option: AttachmentOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Circular icon container
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = option.backgroundColor.copy(alpha = 0.15f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = stringResource(option.labelResId),
                    tint = option.backgroundColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Label
        Text(
            text = stringResource(option.labelResId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Gets current location using FusedLocationProviderClient
 */
private fun getCurrentLocation(context: Context, onLocationReceived: (Double, Double) -> Unit) {
    try {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    onLocationReceived(it.latitude, it.longitude)
                } ?: run {
                    Toast.makeText(context, "Unable to get location", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to get location", Toast.LENGTH_SHORT).show()
            }
    } catch (e: SecurityException) {
        Toast.makeText(context, "Location permission required", Toast.LENGTH_SHORT).show()
    }
}


/**
 * Schedule message dialog for picking a send time
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit
) {
    if (!visible) return

    var selectedDate by remember { mutableStateOf<Long?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val timePickerState = rememberTimePickerState()

    if (!showTimePicker) {
        // Date picker dialog
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDate = it
                            showTimePicker = true
                        }
                    }
                ) {
                    Text("Next")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    } else {
        // Time picker dialog
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedDate?.let { date ->
                            val calendar = Calendar.getInstance().apply {
                                timeInMillis = date
                                set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                                set(Calendar.MINUTE, timePickerState.minute)
                                set(Calendar.SECOND, 0)
                            }
                            onSchedule(calendar.timeInMillis)
                            onDismiss()
                        }
                    }
                ) {
                    Text("Schedule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Back")
                }
            }
        )
    }
}
