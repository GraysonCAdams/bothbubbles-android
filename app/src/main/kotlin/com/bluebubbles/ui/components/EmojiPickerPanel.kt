package com.bluebubbles.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Emoji category with icon and emojis
 */
data class EmojiCategory(
    val id: String,
    val icon: ImageVector,
    val emojis: List<String>
)

/**
 * Slide-up emoji picker panel following Material Design patterns.
 * Provides quick access to common emojis organized by category.
 */
@Composable
fun EmojiPickerPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf("smileys") }

    // Emoji categories with common emojis
    val emojiCategories = remember {
        listOf(
            EmojiCategory(
                id = "smileys",
                icon = Icons.Outlined.EmojiEmotions,
                emojis = listOf(
                    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
                    "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                    "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜",
                    "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐",
                    "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬",
                    "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒",
                    "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵",
                    "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕"
                )
            ),
            EmojiCategory(
                id = "gestures",
                icon = Icons.Outlined.ThumbUp,
                emojis = listOf(
                    "👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌",
                    "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳", "💪",
                    "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠",
                    "👀", "👁️", "👅", "👄", "💋", "🩸", "👋", "🤚",
                    "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                    "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇",
                    "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏"
                )
            ),
            EmojiCategory(
                id = "hearts",
                icon = Icons.Outlined.Favorite,
                emojis = listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
                    "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖",
                    "💘", "💝", "💟", "❤️‍🔥", "❤️‍🩹", "💌", "💋", "😍",
                    "🥰", "😘", "😻", "💑", "👩‍❤️‍👨", "👨‍❤️‍👨", "👩‍❤️‍👩", "💏"
                )
            ),
            EmojiCategory(
                id = "nature",
                icon = Icons.Outlined.Pets,
                emojis = listOf(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
                    "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵",
                    "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇",
                    "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌",
                    "🌸", "💐", "🌷", "🌹", "🥀", "🌺", "🌻", "🌼",
                    "🌱", "🌲", "🌳", "🌴", "🌵", "🌾", "🌿", "☘️"
                )
            ),
            EmojiCategory(
                id = "food",
                icon = Icons.Outlined.Restaurant,
                emojis = listOf(
                    "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓",
                    "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
                    "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🫑",
                    "🍕", "🍔", "🍟", "🌭", "🥪", "🌮", "🌯", "🥙",
                    "🧆", "🥚", "🍳", "🥘", "🍲", "🫕", "🥣", "🥗",
                    "🍿", "🧈", "🧂", "🥫", "🍝", "🍜", "🍛", "🍣"
                )
            ),
            EmojiCategory(
                id = "activities",
                icon = Icons.Outlined.SportsBasketball,
                emojis = listOf(
                    "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉",
                    "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
                    "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿",
                    "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌",
                    "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "🤺",
                    "🎮", "🕹️", "🎲", "🧩", "🎭", "🎨", "🎬", "🎤"
                )
            ),
            EmojiCategory(
                id = "travel",
                icon = Icons.Outlined.Flight,
                emojis = listOf(
                    "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
                    "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🛵", "🏍️",
                    "🛺", "🚲", "🛴", "🚏", "🛤️", "🛣️", "⛽", "🚨",
                    "✈️", "🛫", "🛬", "🛩️", "💺", "🚁", "🚀", "🛸",
                    "🚢", "⛵", "🛥️", "🚤", "⛴️", "🛳️", "🚂", "🚃",
                    "🏠", "🏡", "🏢", "🏣", "🏤", "🏥", "🏦", "🏨"
                )
            ),
            EmojiCategory(
                id = "objects",
                icon = Icons.Outlined.Lightbulb,
                emojis = listOf(
                    "⌚", "📱", "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️",
                    "🖲️", "💽", "💾", "💿", "📀", "🧮", "🎥", "🎞️",
                    "📽️", "🎬", "📺", "📷", "📸", "📹", "📼", "🔍",
                    "🔎", "🕯️", "💡", "🔦", "🏮", "🪔", "📔", "📕",
                    "📖", "📗", "📘", "📙", "📚", "📓", "📒", "📃",
                    "🎁", "🎀", "🎊", "🎉", "🎎", "🎏", "🎐", "🧧"
                )
            ),
            EmojiCategory(
                id = "symbols",
                icon = Icons.Outlined.Tag,
                emojis = listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
                    "💯", "💢", "💥", "💫", "💦", "💨", "🕳️", "💣",
                    "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭", "💤", "✅", "❌",
                    "❓", "❔", "❕", "❗", "⭕", "🔴", "🟠", "🟡",
                    "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔶", "🔷",
                    "🔸", "🔹", "🔺", "🔻", "💠", "🔘", "🔳", "🔲"
                )
            )
        )
    }

    val currentEmojis = remember(selectedCategory) {
        emojiCategories.find { it.id == selectedCategory }?.emojis ?: emptyList()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Category tabs
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(emojiCategories) { category ->
                        val isSelected = category.id == selectedCategory
                        IconButton(
                            onClick = { selectedCategory = category.id },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = category.id,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Emoji grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(currentEmojis) { emoji ->
                        EmojiItem(
                            emoji = emoji,
                            onClick = { onEmojiSelected(emoji) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Individual emoji item in the grid
 */
@Composable
private fun EmojiItem(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = emoji,
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )
    }
}
