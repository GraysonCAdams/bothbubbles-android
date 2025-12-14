package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SoundPickerDialog(
    currentSound: String?,
    onSoundSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sounds = listOf(
        null to "Default",
        "silent" to "Silent",
        "cute_bamboo" to "Cute Bamboo",
        "gentle_chime" to "Gentle Chime",
        "soft_bell" to "Soft Bell",
        "message_tone" to "Message Tone"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification sound") },
        text = {
            Column {
                sounds.forEach { (soundId, soundName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSoundSelected(soundId) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSound == soundId,
                            onClick = { onSoundSelected(soundId) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = soundName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun LockScreenVisibilityDialog(
    currentVisibility: LockScreenVisibility,
    onVisibilitySelected: (LockScreenVisibility) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock screen") },
        text = {
            Column {
                LockScreenVisibility.entries.forEach { visibility ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVisibilitySelected(visibility) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentVisibility == visibility,
                            onClick = { onVisibilitySelected(visibility) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = visibility.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
