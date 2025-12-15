package com.bothbubbles.ui.settings.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bothbubbles.services.socket.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Profile header section matching Google Messages design.
 * Shows server URL and connection status. Tapping navigates to server settings.
 */
@Composable
fun ProfileHeader(
    serverUrl: String,
    connectionState: ConnectionState,
    onManageServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onManageServerClick()
            })
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar area with BlueBubbles icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = "Manage Server",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Server URL
        Text(
            text = serverUrl.ifEmpty { "Not configured" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Connection status with dot - uses MD3 semantic colors
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.NOT_CONFIGURED -> MaterialTheme.colorScheme.error
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "iMessage",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }
    }
}

/**
 * Card container for settings items matching Google Messages design.
 * Uses 28dp corner radius to match Google's design language.
 * Includes staggered entrance animation.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    index: Int = 0,  // For staggered animation
    content: @Composable ColumnScope.() -> Unit
) {
    // Staggered entrance animation
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 30L)  // 30ms stagger
        appeared = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(150),
        label = "cardAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (appeared) 0f else 16f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardTranslation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                this.translationY = translationY
            },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(content = content)
    }
}

/**
 * Individual settings menu item matching Google Messages design.
 * Features a simple icon, title, subtitle, and optional trailing content.
 *
 * Enhanced UX features:
 * - Shake animation when disabled row is tapped (prevents "dead click" confusion)
 * - Actionable subtitles with tappable links
 * - Loading state support for async operations
 * - Contextual help via info button
 *
 * @param onDisabledClick Called when user taps a disabled row. Use to show snackbar/toast explaining why.
 * @param subtitleAction Optional action text appended to subtitle as a tappable link (e.g., "Tap to fix")
 * @param onSubtitleActionClick Called when the subtitle action link is tapped
 * @param isLoading When true, shows loading indicator instead of trailing content
 * @param onInfoClick When provided, shows an info icon that triggers this callback (for contextual help)
 */
@Composable
fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    subtitleAction: String? = null,
    onSubtitleActionClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
    onInfoClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Shake animation state for disabled click feedback
    val shakeOffset = remember { Animatable(0f) }

    fun triggerShake() {
        scope.launch {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 300
                    0f at 0
                    -8f at 50
                    8f at 100
                    -6f at 150
                    6f at 200
                    -3f at 250
                    0f at 300
                }
            )
        }
    }

    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
        },
        supportingContent = if (subtitle != null || subtitleAction != null) {
            {
                if (subtitleAction != null && onSubtitleActionClick != null) {
                    // Actionable subtitle with tappable link
                    val annotatedText = buildAnnotatedString {
                        if (subtitle != null) {
                            append(subtitle)
                            append(" ")
                        }
                        pushStringAnnotation(tag = "action", annotation = "action")
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(subtitleAction)
                        }
                        pop()
                    }
                    ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations(tag = "action", start = offset, end = offset)
                                .firstOrNull()?.let {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSubtitleActionClick()
                                }
                        }
                    )
                } else if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        },
        trailingContent = when {
            isLoading -> {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            onInfoClick != null || trailingContent != null -> {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Info button for contextual help
                        if (onInfoClick != null) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onInfoClick()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "More info",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Original trailing content (e.g., switch)
                        trailingContent?.invoke()
                    }
                }
            }
            else -> null
        },
        modifier = modifier
            .graphicsLayer { translationX = shakeOffset.value }
            .clickable(
                enabled = true, // Always enabled for click handling
                onClick = {
                    if (enabled && !isLoading) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    } else if (!enabled && onDisabledClick != null) {
                        // Trigger shake + callback for disabled state
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerShake()
                        onDisabledClick()
                    }
                }
            ),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

/**
 * Section title for settings groups.
 * MD3 style: uses Primary color and LabelLarge typography for visual hierarchy.
 */
@Composable
fun SettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * Status badge for messaging services (iMessage/SMS).
 * Uses MD3 semantic color roles for consistent theming.
 *
 * Accessibility: Uses icons (checkmark/warning) in addition to color
 * to support colorblind users. Includes chevron to indicate interactivity.
 */
@Composable
fun StatusBadge(
    label: String,
    status: BadgeStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val statusColor = when (status) {
        BadgeStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
        BadgeStatus.ERROR -> MaterialTheme.colorScheme.error
        BadgeStatus.DISABLED -> MaterialTheme.colorScheme.outline
    }

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier
            // Ensure minimum 48dp touch target for accessibility
            .defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Status indicator with icon for accessibility (colorblind support)
            when (status) {
                BadgeStatus.CONNECTED -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Connected",
                        modifier = Modifier.size(14.dp),
                        tint = statusColor
                    )
                }
                BadgeStatus.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        modifier = Modifier.size(14.dp),
                        tint = statusColor
                    )
                }
                BadgeStatus.DISABLED -> {
                    // Hollow circle for disabled state
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .border(1.5.dp, statusColor, CircleShape)
                    )
                }
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Chevron to indicate badge is tappable/navigable
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class BadgeStatus {
    CONNECTED,
    ERROR,
    DISABLED
}

/**
 * Messaging section header with status badges for iMessage and SMS
 */
@Composable
fun MessagingSectionHeader(
    iMessageStatus: BadgeStatus,
    smsStatus: BadgeStatus,
    onIMessageClick: () -> Unit,
    onSmsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Messaging",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(
                label = "iMessage",
                status = iMessageStatus,
                onClick = onIMessageClick
            )
            StatusBadge(
                label = "SMS",
                status = smsStatus,
                onClick = onSmsClick
            )
        }
    }
}

/**
 * MD3 Switch with thumb icons.
 * Shows a checkmark when enabled, X when disabled.
 * Use for important toggles like Private API to emphasize state.
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showIcons: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    Switch(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange(it)
        },
        enabled = enabled,
        modifier = modifier,
        thumbContent = if (showIcons) {
            {
                Icon(
                    imageVector = if (checked) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else null
    )
}
