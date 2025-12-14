package com.bothbubbles.ui.components.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bothbubbles.util.AvatarGenerator

/**
 * Message source type for determining which indicator to show
 */
enum class MessageSourceType {
    IMESSAGE,
    SMS,
    NONE
}

/**
 * Avatar component for displaying user/contact avatars
 * with smooth fade-in animation when image loads
 *
 * @param name The contact name or phone number to display
 * @param modifier Modifier for the avatar
 * @param avatarPath Optional path to contact photo
 * @param size Size of the avatar
 * @param isBusiness If true, shows building icon (for business contacts without personal name)
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    size: Dp = 40.dp,
    isBusiness: Boolean = false
) {
    val initials = remember(name) { AvatarGenerator.getInitials(name) }
    val backgroundColor = remember(name) { Color(AvatarGenerator.getAvatarColorInt(name)) }
    val showBuildingIcon = remember(name) { AvatarGenerator.isShortCodeOrAlphanumericSender(name) } || isBusiness
    val showPersonIcon = remember(name) { AvatarGenerator.isPhoneNumber(name) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Show placeholder (initials, person icon, or building icon)
        when {
            showBuildingIcon -> {
                Icon(
                    Icons.Default.Business,
                    contentDescription = "Business",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
            showPersonIcon -> {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Contact",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
            else -> {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = (size.value / 2.5f).sp
                    ),
                    color = Color.White
                )
            }
        }

        // Overlay with contact photo if available (fast crossfade for Android 16 style)
        if (avatarPath != null) {
            val context = LocalContext.current
            val imageRequest = remember(avatarPath) {
                ImageRequest.Builder(context)
                    .data(Uri.parse(avatarPath))
                    .crossfade(150)  // Fast 150ms crossfade
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = "Avatar for $name",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// Avatar colors and utility functions are now in AvatarGenerator.kt
// to share logic between UI and notifications

// Extension for sp unit in Dp context
private val Float.sp: androidx.compose.ui.unit.TextUnit
    get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)

// ====================
// Preview Functions
// ====================

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar - Text")
@Composable
private fun AvatarTextPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Avatar(
            name = "John Appleseed",
            avatarPath = null,
            size = 56.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar - Shortcode")
@Composable
private fun AvatarShortcodePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(name = "60484", avatarPath = null, size = 56.dp)
            Avatar(name = "GOOGLE", avatarPath = null, size = 56.dp)
            Avatar(name = "AMZN", avatarPath = null, size = 56.dp)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar - Business")
@Composable
private fun AvatarBusinessPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Avatar(
            name = "Acme Corporation",
            avatarPath = null,
            size = 56.dp,
            isBusiness = true
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar - Small")
@Composable
private fun AvatarSmallPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Avatar(
            name = "Jane Doe",
            avatarPath = null,
            size = 32.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar - Large")
@Composable
private fun AvatarLargePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        Avatar(
            name = "Bob Smith",
            avatarPath = null,
            size = 80.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group Avatar - 2 Members")
@Composable
private fun GroupAvatarTwoPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        GroupAvatar(
            names = listOf("Alice", "Bob"),
            avatarPaths = listOf(null, null),
            size = 56.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group Avatar - 3 Members")
@Composable
private fun GroupAvatarThreePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        GroupAvatar(
            names = listOf("Alice", "Bob", "Charlie"),
            avatarPaths = listOf(null, null, null),
            size = 56.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Group Avatar - 4+ Members")
@Composable
private fun GroupAvatarFourPlusPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        GroupAvatar(
            names = listOf("Alice", "Bob", "Charlie", "Diana"),
            avatarPaths = listOf(null, null, null, null),
            size = 56.dp
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar with iMessage Badge")
@Composable
private fun AvatarWithIMessageBadgePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        AvatarWithMessageType(
            messageSourceType = MessageSourceType.IMESSAGE,
            size = 56.dp
        ) {
            Avatar(name = "John Appleseed", avatarPath = null, size = 56.dp)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Avatar with SMS Badge")
@Composable
private fun AvatarWithSmsBadgePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        AvatarWithMessageType(
            messageSourceType = MessageSourceType.SMS,
            size = 56.dp
        ) {
            Avatar(name = "Jane Doe", avatarPath = null, size = 56.dp)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    name = "Avatar - Dark Mode",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun AvatarDarkModePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper(darkTheme = true) {
        Avatar(
            name = "John Appleseed",
            avatarPath = null,
            size = 56.dp
        )
    }
}
