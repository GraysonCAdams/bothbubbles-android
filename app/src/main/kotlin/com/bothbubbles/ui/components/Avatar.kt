package com.bothbubbles.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.net.Uri
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
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
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    size: Dp = 40.dp
) {
    val initials = remember(name) { AvatarGenerator.getInitials(name) }
    val backgroundColor = remember(name) { Color(AvatarGenerator.getAvatarColorInt(name)) }
    val showPersonIcon = remember(name) { AvatarGenerator.isPhoneNumber(name) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Show placeholder (initials or person icon)
        if (showPersonIcon) {
            Icon(
                Icons.Default.Person,
                contentDescription = "Contact",
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f)
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (size.value / 2.5f).sp
                ),
                color = Color.White
            )
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

/**
 * Group avatar showing multiple participants
 */
@Composable
fun GroupAvatar(
    names: List<String>,
    modifier: Modifier = Modifier,
    avatarPaths: List<String?> = emptyList(),
    size: Dp = 56.dp
) {
    val displayCount = minOf(names.size, 4)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (displayCount) {
            0, 1 -> {
                // Single or no participants, show group icon
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(size)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Group,
                            contentDescription = "Group",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(size * 0.5f)
                        )
                    }
                }
            }
            2 -> {
                // Two participants, offset circles
                val smallSize = size * 0.65f
                Box(modifier = Modifier.size(size)) {
                    Avatar(
                        name = names.getOrElse(0) { "?" },
                        avatarPath = avatarPaths.getOrNull(0),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    ) {
                        Avatar(
                            name = names.getOrElse(1) { "?" },
                            avatarPath = avatarPaths.getOrNull(1),
                            size = smallSize
                        )
                    }
                }
            }
            3 -> {
                // Three participants in triangle
                val smallSize = size * 0.5f
                Box(modifier = Modifier.size(size)) {
                    Avatar(
                        name = names.getOrElse(0) { "?" },
                        avatarPath = avatarPaths.getOrNull(0),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    Avatar(
                        name = names.getOrElse(1) { "?" },
                        avatarPath = avatarPaths.getOrNull(1),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                    Avatar(
                        name = names.getOrElse(2) { "?" },
                        avatarPath = avatarPaths.getOrNull(2),
                        size = smallSize,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
            else -> {
                // Four participants in grid
                val smallSize = size * 0.48f
                Column(
                    modifier = Modifier.size(size),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Avatar(
                            name = names.getOrElse(0) { "?" },
                            avatarPath = avatarPaths.getOrNull(0),
                            size = smallSize
                        )
                        Avatar(
                            name = names.getOrElse(1) { "?" },
                            avatarPath = avatarPaths.getOrNull(1),
                            size = smallSize
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Avatar(
                            name = names.getOrElse(2) { "?" },
                            avatarPath = avatarPaths.getOrNull(2),
                            size = smallSize
                        )
                        if (names.size > 4) {
                            // Show +N indicator
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape,
                                modifier = Modifier.size(smallSize)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+${names.size - 3}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Avatar(
                                name = names.getOrElse(3) { "?" },
                                avatarPath = avatarPaths.getOrNull(3),
                                size = smallSize
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Avatar with online indicator
 */
@Composable
fun AvatarWithStatus(
    name: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    size: Dp = 56.dp
) {
    Box(modifier = modifier.size(size)) {
        Avatar(
            name = name,
            avatarPath = avatarPath,
            size = size
        )

        if (isOnline) {
            Surface(
                color = Color(0xFF4CAF50), // Green
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.25f)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            ) {}
        }
    }
}

/**
 * Conversation avatar that handles both single and group chats
 */
@Composable
fun ConversationAvatar(
    displayName: String,
    isGroup: Boolean,
    participantNames: List<String> = emptyList(),
    avatarPath: String? = null,
    participantAvatars: List<String?> = emptyList(),
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    if (isGroup && participantNames.size > 1) {
        GroupAvatar(
            names = participantNames,
            avatarPaths = participantAvatars,
            size = size,
            modifier = modifier
        )
    } else {
        Avatar(
            name = displayName,
            avatarPath = avatarPath,
            size = size,
            modifier = modifier
        )
    }
}

// iMessage and SMS indicator colors
private val iMessageBlue = Color(0xFF007AFF)
private val smsGreen = Color(0xFF34C759)

/**
 * Avatar wrapper that displays a message type indicator (iMessage cloud or SMS cellular)
 * in the bottom right corner with an outline that "cuts into" the avatar.
 *
 * @param messageSourceType The type of the last message (IMESSAGE, SMS, or NONE)
 * @param backgroundColor Background color for the indicator outline (should match the list background)
 * @param size The size of the avatar
 * @param indicatorSizeOverride Optional explicit size for the indicator badge. If null, defaults to size * 0.36f
 * @param avatarContent The avatar composable to wrap (Avatar or GroupAvatar)
 */
@Composable
fun AvatarWithMessageType(
    messageSourceType: MessageSourceType,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    size: Dp = 56.dp,
    indicatorSizeOverride: Dp? = null,
    avatarContent: @Composable () -> Unit
) {
    val indicatorSize = indicatorSizeOverride ?: (size * 0.36f)
    val badgeOverflow = 4.dp // How much the badge extends beyond the avatar

    // Outer box sized to accommodate badge overflow
    Box(
        modifier = modifier.size(size + badgeOverflow),
        contentAlignment = Alignment.TopStart
    ) {
        // Avatar positioned to leave room for badge
        Box(modifier = Modifier.size(size)) {
            avatarContent()
        }

        if (messageSourceType != MessageSourceType.NONE) {
            val iconSize = indicatorSize * 0.6f
            val indicatorColor = when (messageSourceType) {
                MessageSourceType.IMESSAGE -> iMessageBlue
                MessageSourceType.SMS -> smsGreen
                else -> Color.Transparent
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(indicatorSize)
                    .border(2.dp, backgroundColor, CircleShape)
                    .clip(CircleShape)
                    .background(indicatorColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (messageSourceType) {
                        MessageSourceType.IMESSAGE -> Icons.Default.Cloud
                        MessageSourceType.SMS -> Icons.Default.CellTower
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = when (messageSourceType) {
                        MessageSourceType.IMESSAGE -> "iMessage"
                        MessageSourceType.SMS -> "SMS"
                        else -> null
                    },
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * Helper function to convert message source string to MessageSourceType
 */
fun getMessageSourceType(messageSource: String?): MessageSourceType {
    return when (messageSource) {
        "IMESSAGE" -> MessageSourceType.IMESSAGE
        "SERVER_SMS", "LOCAL_SMS", "LOCAL_MMS" -> MessageSourceType.SMS
        else -> MessageSourceType.NONE
    }
}
