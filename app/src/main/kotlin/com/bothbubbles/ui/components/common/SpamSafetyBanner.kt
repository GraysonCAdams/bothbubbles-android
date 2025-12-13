package com.bothbubbles.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Banner displayed at the bottom of spam conversations.
 * Shows "This conversation is in your spam folder" with a centered "Mark as safe" button.
 *
 * When tapped, the conversation is removed from spam and the sender is whitelisted.
 */
@Composable
fun SpamSafetyBanner(
    onMarkAsSafe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "This conversation is in your spam folder",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onMarkAsSafe) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Mark as safe")
        }
    }
}
