package com.bluebubbles.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for selecting a message effect before sending.
 * iOS-style long-press on send button opens this picker.
 *
 * @param messageText The message text being sent (for preview)
 * @param onEffectSelected Callback with selected effect (null for no effect)
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectPickerSheet(
    messageText: String,
    onEffectSelected: (MessageEffect?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedEffect by remember { mutableStateOf<MessageEffect?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Send with effect",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Message preview
            MessagePreview(
                text = messageText,
                effect = selectedEffect
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bubble effects section
            Text(
                text = "BUBBLE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(bubbleEffects) { effectInfo ->
                    EffectChip(
                        effectInfo = effectInfo,
                        isSelected = selectedEffect == effectInfo.effect,
                        onClick = {
                            selectedEffect = if (selectedEffect == effectInfo.effect) {
                                null
                            } else {
                                effectInfo.effect
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Screen effects section
            Text(
                text = "SCREEN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(screenEffects) { effectInfo ->
                    EffectChip(
                        effectInfo = effectInfo,
                        isSelected = selectedEffect == effectInfo.effect,
                        onClick = {
                            selectedEffect = if (selectedEffect == effectInfo.effect) {
                                null
                            } else {
                                effectInfo.effect
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Send button
            Button(
                onClick = { onEffectSelected(selectedEffect) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedEffect != null) {
                        "Send with ${selectedEffect?.displayName}"
                    } else {
                        "Send without effect"
                    }
                )
            }
        }
    }
}

/**
 * Preview of the message with the selected effect styling.
 */
@Composable
private fun MessagePreview(
    text: String,
    effect: MessageEffect?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Simple message bubble preview
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = text.ifEmpty { "Your message" },
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                maxLines = 2
            )
        }

        // Effect indicator overlay
        if (effect != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape
                    )
                    .padding(6.dp)
            ) {
                Text(
                    text = getEffectEmoji(effect),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * Selectable effect chip.
 */
@Composable
private fun EffectChip(
    effectInfo: EffectInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(8.dp)
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = effectInfo.icon,
                contentDescription = effectInfo.name,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = effectInfo.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Effect metadata for display in the picker.
 */
private data class EffectInfo(
    val effect: MessageEffect,
    val name: String,
    val icon: ImageVector
)

private val bubbleEffects = listOf(
    EffectInfo(
        effect = MessageEffect.Bubble.Slam,
        name = "Slam",
        icon = Icons.Default.Star
    ),
    EffectInfo(
        effect = MessageEffect.Bubble.Loud,
        name = "Loud",
        icon = Icons.Default.VolumeUp
    ),
    EffectInfo(
        effect = MessageEffect.Bubble.Gentle,
        name = "Gentle",
        icon = Icons.Default.Favorite
    ),
    EffectInfo(
        effect = MessageEffect.Bubble.InvisibleInk,
        name = "Invisible Ink",
        icon = Icons.Default.VisibilityOff
    )
)

private val screenEffects = listOf(
    EffectInfo(
        effect = MessageEffect.Screen.Echo,
        name = "Echo",
        icon = Icons.Default.MusicNote
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Spotlight,
        name = "Spotlight",
        icon = Icons.Default.LightMode
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Balloons,
        name = "Balloons",
        icon = Icons.Default.Celebration
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Confetti,
        name = "Confetti",
        icon = Icons.Default.Star
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Hearts,
        name = "Love",
        icon = Icons.Default.Favorite
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Lasers,
        name = "Lasers",
        icon = Icons.Default.Visibility
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Fireworks,
        name = "Fireworks",
        icon = Icons.Default.Celebration
    ),
    EffectInfo(
        effect = MessageEffect.Screen.Celebration,
        name = "Celebration",
        icon = Icons.Default.Star
    )
)

private fun getEffectEmoji(effect: MessageEffect): String {
    return when (effect) {
        MessageEffect.Bubble.Slam -> "💥"
        MessageEffect.Bubble.Loud -> "📢"
        MessageEffect.Bubble.Gentle -> "🌸"
        MessageEffect.Bubble.InvisibleInk -> "🔮"
        MessageEffect.Screen.Echo -> "🔊"
        MessageEffect.Screen.Spotlight -> "💡"
        MessageEffect.Screen.Balloons -> "🎈"
        MessageEffect.Screen.Confetti -> "🎊"
        MessageEffect.Screen.Hearts -> "❤️"
        MessageEffect.Screen.Lasers -> "🔦"
        MessageEffect.Screen.Fireworks -> "🎆"
        MessageEffect.Screen.Celebration -> "✨"
    }
}
