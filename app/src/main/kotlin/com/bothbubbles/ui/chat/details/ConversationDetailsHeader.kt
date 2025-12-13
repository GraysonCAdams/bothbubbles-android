package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.ConversationAvatar
import com.bothbubbles.util.PhoneNumberFormatter

@Composable
fun ConversationHeader(
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
            size = 96.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = PhoneNumberFormatter.format(displayName),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        // Show subtitle only if it's different from the display name (after formatting)
        val formattedSubtitle = PhoneNumberFormatter.format(subtitle)
        val formattedDisplayName = PhoneNumberFormatter.format(displayName)
        if (subtitle.isNotBlank() && formattedSubtitle != formattedDisplayName) {
            Text(
                text = formattedSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
