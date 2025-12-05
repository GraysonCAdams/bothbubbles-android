package com.bluebubbles.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ripple
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/**
 * Enum representing available call methods
 */
enum class CallMethod(val displayName: String) {
    GOOGLE_MEET("Google Meet"),
    WHATSAPP("WhatsApp")
}

/**
 * Call button with long-press dropdown for selecting call method.
 *
 * - Tap: Initiates call using the preferred method
 * - Long-press: Shows dropdown to select method (selection updates preference)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallMethodButton(
    preferredMethod: CallMethod,
    isWhatsAppAvailable: Boolean,
    onCall: (CallMethod) -> Unit,
    onMethodSelected: (CallMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = modifier) {
        IconButton(
            onClick = { onCall(preferredMethod) },
            modifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                role = Role.Button,
                onClick = { onCall(preferredMethod) },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    showDropdown = true
                }
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.Videocam,
                contentDescription = "Video call",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        CallMethodDropdown(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false },
            preferredMethod = preferredMethod,
            isWhatsAppAvailable = isWhatsAppAvailable,
            onMethodSelected = { method ->
                onMethodSelected(method)
                onCall(method)
                showDropdown = false
            }
        )
    }
}

/**
 * Dropdown menu for selecting call method
 */
@Composable
fun CallMethodDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    preferredMethod: CallMethod,
    isWhatsAppAvailable: Boolean,
    onMethodSelected: (CallMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        // Google Meet option (always available)
        DropdownMenuItem(
            text = { Text(CallMethod.GOOGLE_MEET.displayName) },
            onClick = { onMethodSelected(CallMethod.GOOGLE_MEET) },
            leadingIcon = {
                if (preferredMethod == CallMethod.GOOGLE_MEET) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        // WhatsApp option (only if available)
        if (isWhatsAppAvailable) {
            DropdownMenuItem(
                text = { Text(CallMethod.WHATSAPP.displayName) },
                onClick = { onMethodSelected(CallMethod.WHATSAPP) },
                leadingIcon = {
                    if (preferredMethod == CallMethod.WHATSAPP) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    }
}
