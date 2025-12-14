package com.bothbubbles.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Prominent banner shown at the top of the chat screen when navigation is detected.
 * Provides one-tap access to start ETA sharing - safe for use while driving.
 *
 * Two modes:
 * 1. "Offer" mode: Navigation detected, user can tap to start sharing
 * 2. "Active" mode: Currently sharing, shows status and stop button
 */
@Composable
fun EtaSharingBanner(
    isNavigationActive: Boolean,
    isCurrentlySharing: Boolean,
    currentEtaMinutes: Int,
    destination: String?,
    recipientName: String?,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show banner if navigation is active (either to offer sharing or show active status)
    AnimatedVisibility(
        visible = isNavigationActive,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        if (isCurrentlySharing) {
            // Active sharing banner
            ActiveSharingBanner(
                etaMinutes = currentEtaMinutes,
                destination = destination,
                recipientName = recipientName,
                onStopSharing = onStopSharing
            )
        } else {
            // Offer to share banner
            OfferSharingBanner(
                etaMinutes = currentEtaMinutes,
                destination = destination,
                onStartSharing = onStartSharing
            )
        }
    }
}

/**
 * Banner offering to start ETA sharing
 */
@Composable
private fun OfferSharingBanner(
    etaMinutes: Int,
    destination: String?,
    onStartSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = buildString {
                    append("📍 Navigation Detected")
                    if (etaMinutes > 0) {
                        append(" (${formatEta(etaMinutes)})")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Button(
            onClick = onStartSharing,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("Share ETA")
        }
    }
}

/**
 * Banner showing active ETA sharing status
 */
@Composable
private fun ActiveSharingBanner(
    etaMinutes: Int,
    destination: String?,
    recipientName: String?,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onStopSharing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Pulsing navigation icon would be nice here
            Icon(
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = buildString {
                    append("📍 Sharing ETA")
                    if (etaMinutes > 0) {
                        append(" • ${formatEta(etaMinutes)}")
                    }
                    destination?.let {
                        if (it.length <= 20) append(" to $it")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        IconButton(onClick = onStopSharing) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Stop sharing",
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

private fun formatEta(minutes: Int): String {
    return when {
        minutes < 1 -> "Arriving"
        minutes < 60 -> "$minutes min"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        }
    }
}
