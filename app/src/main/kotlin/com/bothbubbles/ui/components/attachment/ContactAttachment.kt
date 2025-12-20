package com.bothbubbles.ui.components.attachment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.components.common.openVCardContactFromPath
import com.bothbubbles.ui.components.message.AttachmentUiModel

/**
 * Renders a VCard/Contact attachment as a card with contact info.
 * Displays contact name extracted from filename, with an "Add Contact" indicator.
 * Tapping opens the device's contacts app to add/view the contact.
 *
 * @param attachment The attachment to render (must be a VCard type)
 * @param modifier Modifier for the root composable
 * @param interactions Shared interaction callbacks (not used for click - handled internally)
 * @param isFromMe Whether this message is from the current user (affects theming)
 */
@Composable
fun ContactAttachment(
    attachment: AttachmentUiModel,
    modifier: Modifier = Modifier,
    interactions: AttachmentInteractions,
    isFromMe: Boolean = false
) {
    val context = LocalContext.current

    // Extract contact name from filename (format: Name_timestamp.vcf)
    val contactName = attachment.transferName
        ?.substringBeforeLast("_")
        ?.replace("_", " ")
        ?: "Contact"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFromMe) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = modifier
            .widthIn(min = 180.dp, max = 250.dp)
            .clickable {
                // Open vCard in contacts app to add/view the contact
                val path = attachment.localPath
                if (path != null) {
                    openVCardContactFromPath(context, path)
                } else {
                    Toast.makeText(context, "Contact not downloaded yet", Toast.LENGTH_SHORT).show()
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Contact icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ContactPage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Contact info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "Contact Card",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Add contact icon
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = "Add contact",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
