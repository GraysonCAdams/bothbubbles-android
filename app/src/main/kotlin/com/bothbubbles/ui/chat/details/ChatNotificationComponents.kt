package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.ConversationAvatar

@Composable
internal fun NotificationSettingsHeader(
    displayName: String,
    subtitle: String,
    isGroup: Boolean,
    participantNames: List<String>,
    avatarPath: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 16.dp)
    ) {
        ConversationAvatar(
            displayName = displayName,
            isGroup = isGroup,
            participantNames = participantNames,
            avatarPath = avatarPath,
            size = 80.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun NotificationToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!isEnabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Show notifications",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}
