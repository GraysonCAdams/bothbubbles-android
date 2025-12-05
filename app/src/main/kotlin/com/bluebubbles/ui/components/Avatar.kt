package com.bluebubbles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import kotlin.math.abs

/**
 * Avatar component for displaying user/contact avatars
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    size: Dp = 40.dp
) {
    val initials = remember(name) { getInitials(name) }
    val backgroundColor = remember(name) { getAvatarColor(name) }
    val showPersonIcon = remember(name) { isPhoneNumber(name) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        when {
            avatarPath != null -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(avatarPath)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Avatar for $name",
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop
                )
            }
            showPersonIcon -> {
                // Show person icon for phone numbers (Google Messages style)
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
    }
}

private fun getInitials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
        parts.size == 1 && parts.first().length >= 2 -> parts.first().take(2)
        parts.size == 1 -> parts.first().take(1)
        else -> "?"
    }.uppercase()
}

// Google Messages-style avatar colors
private val avatarColors = listOf(
    Color(0xFF1A73E8), // Blue
    Color(0xFF34A853), // Green
    Color(0xFFEA4335), // Red
    Color(0xFFFBBC04), // Yellow/Amber
    Color(0xFF9334E6), // Purple
    Color(0xFF00ACC1), // Cyan/Teal
    Color(0xFFE91E63), // Pink
    Color(0xFF8E24AA), // Violet
)

// Default color for phone numbers
private val phoneNumberAvatarColor = Color(0xFFFBBC04) // Yellow/Amber

private fun getAvatarColor(name: String): Color {
    // Use yellow/amber for phone numbers (like Google Messages)
    if (isPhoneNumber(name)) {
        return phoneNumberAvatarColor
    }
    val hash = abs(name.hashCode())
    return avatarColors[hash % avatarColors.size]
}

private fun isPhoneNumber(name: String): Boolean {
    // Check if name looks like a phone number
    val digitsOnly = name.replace(Regex("[^0-9]"), "")
    return digitsOnly.length >= 7 && name.matches(Regex("^[+\\d\\s()\\-]+$"))
}

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
