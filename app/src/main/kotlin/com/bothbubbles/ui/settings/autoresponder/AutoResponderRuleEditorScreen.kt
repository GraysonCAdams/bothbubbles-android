package com.bothbubbles.ui.settings.autoresponder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.services.context.DndModeType
import com.bothbubbles.util.HapticUtils
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoResponderRuleEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: AutoResponderRuleEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "Edit Rule" else "New Rule")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = viewModel::save) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            AutoResponderRuleEditorContent(
                modifier = Modifier.padding(padding),
                uiState = uiState,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun AutoResponderRuleEditorContent(
    modifier: Modifier = Modifier,
    uiState: AutoResponderRuleEditorUiState,
    viewModel: AutoResponderRuleEditorViewModel
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Basic Info Section
        SectionHeader(title = "Basic Info", icon = Icons.Default.Send)

        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::setName,
            label = { Text("Rule Name") },
            placeholder = { Text("e.g., Night Mode, Driving Response") },
            singleLine = true,
            isError = uiState.nameError != null,
            supportingText = uiState.nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.message,
            onValueChange = viewModel::setMessage,
            label = { Text("Auto-Response Message") },
            placeholder = { Text("The message to send automatically") },
            isError = uiState.messageError != null,
            supportingText = uiState.messageError?.let { { Text(it) } },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            title = "Enabled",
            subtitle = "Rule will be active when enabled",
            checked = uiState.isEnabled,
            onCheckedChange = viewModel::setEnabled
        )

        SectionDivider()

        // Source Filtering Section
        SectionHeader(title = "Source Filtering", icon = Icons.Default.Send)

        Text(
            text = "Only trigger for messages from specific platforms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        ChipGroup(
            items = uiState.availableStitches,
            selectedIds = uiState.selectedStitchIds,
            onToggle = viewModel::toggleStitch,
            itemLabel = { it.displayName },
            itemId = { it.id }
        )

        if (uiState.selectedStitchIds.isEmpty()) {
            Text(
                text = "No filter - triggers for all platforms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ToggleRow(
            title = "First-time senders only",
            subtitle = "Only respond to contacts who haven't received an auto-response before",
            checked = uiState.firstTimeFromSender,
            onCheckedChange = viewModel::setFirstTimeFromSender,
            icon = Icons.Default.Person
        )

        SectionDivider()

        // Time Conditions Section
        SectionHeader(title = "Time Conditions", icon = Icons.Default.AccessTime)

        Text(
            text = "Days of week",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))

        DayOfWeekChips(
            selectedDays = uiState.daysOfWeek,
            onToggle = viewModel::toggleDayOfWeek
        )

        if (uiState.daysOfWeek.isEmpty()) {
            Text(
                text = "No filter - triggers on all days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TimeRangeSection(
            startMinutes = uiState.timeStartMinutes,
            endMinutes = uiState.timeEndMinutes,
            onTimeRangeChange = viewModel::setTimeRange,
            onClear = viewModel::clearTimeRange
        )

        SectionDivider()

        // System State Section
        SectionHeader(title = "System State", icon = Icons.Default.DoNotDisturb)

        Text(
            text = "Do Not Disturb modes",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))

        DndModeChips(
            selectedModes = uiState.dndModes,
            onToggle = viewModel::toggleDndMode
        )

        if (uiState.dndModes.isEmpty()) {
            Text(
                text = "No DND requirement",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ToggleRow(
            title = "Require Android Auto",
            subtitle = "Only trigger when connected to Android Auto (driving mode)",
            checked = uiState.requireDriving,
            onCheckedChange = viewModel::setRequireDriving,
            icon = Icons.Default.DirectionsCar
        )

        ToggleRow(
            title = "Require on call",
            subtitle = "Only trigger when actively on a phone call",
            checked = uiState.requireOnCall,
            onCheckedChange = viewModel::setRequireOnCall,
            icon = Icons.Default.Call
        )

        SectionDivider()

        // Location Section
        SectionHeader(title = "Location", icon = Icons.Default.LocationOn)

        LocationSection(
            locationName = uiState.locationName,
            locationLat = uiState.locationLat,
            locationLng = uiState.locationLng,
            radiusMeters = uiState.locationRadiusMeters,
            inside = uiState.locationInside,
            onLocationSet = viewModel::setLocation,
            onClear = viewModel::clearLocation
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = viewModel::save,
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(if (uiState.isEditing) "Save Changes" else "Create Rule")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                HapticUtils.onConfirm(haptic)
                onCheckedChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                HapticUtils.onConfirm(haptic)
                onCheckedChange(it)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    items: List<T>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    itemLabel: (T) -> String,
    itemId: (T) -> String
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val id = itemId(item)
            FilterChip(
                selected = id in selectedIds,
                onClick = { onToggle(id) },
                label = { Text(itemLabel(item)) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayOfWeekChips(
    selectedDays: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit
) {
    val days = listOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun"
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        days.forEach { (day, label) ->
            FilterChip(
                selected = day in selectedDays,
                onClick = { onToggle(day) },
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DndModeChips(
    selectedModes: Set<DndModeType>,
    onToggle: (DndModeType) -> Unit
) {
    val modes = listOf(
        DndModeType.PRIORITY_ONLY to "Priority Only",
        DndModeType.ALARMS_ONLY to "Alarms Only",
        DndModeType.TOTAL_SILENCE to "Total Silence"
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { (mode, label) ->
            FilterChip(
                selected = mode in selectedModes,
                onClick = { onToggle(mode) },
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeSection(
    startMinutes: Int?,
    endMinutes: Int?,
    onTimeRangeChange: (Int?, Int?) -> Unit,
    onClear: () -> Unit
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "Time range",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (startMinutes != null && endMinutes != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${formatTimeFromMinutes(startMinutes)} - ${formatTimeFromMinutes(endMinutes)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = startMinutes?.let { formatTimeFromMinutes(it) } ?: "",
                    onValueChange = {},
                    label = { Text("Start") },
                    placeholder = { Text("9:00 AM") },
                    readOnly = true,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showStartPicker = true }
                )
                OutlinedTextField(
                    value = endMinutes?.let { formatTimeFromMinutes(it) } ?: "",
                    onValueChange = {},
                    label = { Text("End") },
                    placeholder = { Text("5:00 PM") },
                    readOnly = true,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showEndPicker = true }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap to set a time range. Supports overnight ranges (e.g., 10 PM - 7 AM).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialMinutes = startMinutes ?: (9 * 60),
            onConfirm = { minutes ->
                onTimeRangeChange(minutes, endMinutes ?: (17 * 60))
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showEndPicker) {
        TimePickerDialog(
            initialMinutes = endMinutes ?: (17 * 60),
            onConfirm = { minutes ->
                onTimeRangeChange(startMinutes ?: (9 * 60), minutes)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = timePickerState.hour * 60 + timePickerState.minute
                onConfirm(minutes)
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimeFromMinutes(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    val period = if (hours < 12) "AM" else "PM"
    val displayHour = when {
        hours == 0 -> 12
        hours > 12 -> hours - 12
        else -> hours
    }
    return if (mins == 0) "$displayHour $period" else "$displayHour:${mins.toString().padStart(2, '0')} $period"
}

@Composable
private fun LocationSection(
    locationName: String,
    locationLat: Double?,
    locationLng: Double?,
    radiusMeters: Int,
    inside: Boolean,
    onLocationSet: (String, Double?, Double?, Int, Boolean) -> Unit,
    onClear: () -> Unit
) {
    Column {
        if (locationLat != null && locationLng != null && locationName.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (inside) "At $locationName" else "Away from $locationName",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Radius: ${radiusMeters}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = locationName,
                onValueChange = { onLocationSet(it, locationLat, locationLng, radiusMeters, inside) },
                label = { Text("Location Name") },
                placeholder = { Text("e.g., Home, Work") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Location-based rules require the location permission. " +
                    "Currently, manual coordinate entry is not supported - this feature is coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
