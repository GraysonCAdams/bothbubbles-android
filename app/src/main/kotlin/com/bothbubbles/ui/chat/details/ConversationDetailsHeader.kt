package com.bothbubbles.ui.chat.details

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.ConversationAvatar
import com.bothbubbles.util.PhoneNumberFormatter

/**
 * Original static header for backward compatibility.
 */
@Composable
fun ConversationHeader(
    displayName: String,
    subtitle: String,
    isGroup: Boolean,
    participantNames: List<String>,
    participantAvatars: List<String?> = emptyList(),
    avatarPath: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 0.dp, bottom = 16.dp)
    ) {
        ConversationAvatar(
            displayName = displayName,
            isGroup = isGroup,
            participantNames = participantNames,
            participantAvatars = participantAvatars,
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

/**
 * Collapsing header that animates with the LargeTopAppBar.
 * - Expanded: Shows large 96dp avatar and full name
 * - Collapsed: Fades out as avatar moves to toolbar
 *
 * @param collapseProgress 0 = fully expanded, 1 = fully collapsed
 */
@Composable
fun CollapsingConversationHeader(
    displayName: String,
    subtitle: String,
    isGroup: Boolean,
    participantNames: List<String>,
    participantAvatars: List<String?> = emptyList(),
    avatarPath: String?,
    collapseProgress: Float
) {
    // Fade out and scale down as we collapse
    val alpha = 1f - collapseProgress
    val scale = 1f - (collapseProgress * 0.3f)  // Scale from 1.0 to 0.7

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 0.dp, bottom = 16.dp)
            .alpha(alpha)
            .scale(scale)
    ) {
        ConversationAvatar(
            displayName = displayName,
            isGroup = isGroup,
            participantNames = participantNames,
            participantAvatars = participantAvatars,
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
