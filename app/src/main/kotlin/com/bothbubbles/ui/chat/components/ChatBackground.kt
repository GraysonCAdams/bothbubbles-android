package com.bothbubbles.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Renders the chat background, supporting the default theme background
 * or chat-specific wallpapers and gradients.
 *
 * @param chatGuid The chat GUID to look up specific wallpaper settings (reserved for future use)
 * @param isDarkTheme Whether dark theme is active
 * @param modifier Modifier for the background container
 * @param content The chat content to render on top of the background
 */
@Composable
fun ChatBackground(
    chatGuid: String,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // TODO: Add support for per-chat wallpaper lookup using chatGuid
    // TODO: Add gradient background options based on user preferences

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        content = content
    )
}

/**
 * Simplified overload for when chat-specific settings are not needed.
 */
@Composable
fun ChatBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = content
    )
}
