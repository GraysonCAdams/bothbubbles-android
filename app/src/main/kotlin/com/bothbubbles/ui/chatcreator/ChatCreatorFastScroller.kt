package com.bothbubbles.ui.chatcreator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 style alphabet fast scroller for contacts list.
 * Shows letters on the right side that can be tapped or dragged to jump to sections.
 */
@Composable
fun AlphabetFastScroller(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var containerHeight by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var currentLetter by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .onSizeChanged { containerHeight = it.height }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val letterIndex = (offset.y / containerHeight * letters.size).toInt()
                            .coerceIn(0, letters.size - 1)
                        currentLetter = letters[letterIndex]
                        onLetterSelected(letters[letterIndex])
                    },
                    onDragEnd = {
                        isDragging = false
                        currentLetter = null
                    },
                    onDragCancel = {
                        isDragging = false
                        currentLetter = null
                    },
                    onVerticalDrag = { change, _ ->
                        val letterIndex = (change.position.y / containerHeight * letters.size).toInt()
                            .coerceIn(0, letters.size - 1)
                        if (currentLetter != letters[letterIndex]) {
                            currentLetter = letters[letterIndex]
                            onLetterSelected(letters[letterIndex])
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                val isHighlighted = isDragging && currentLetter == letter
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (isHighlighted) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onLetterSelected(letter) },
                    contentAlignment = Alignment.Center
                ) {
                    if (letter == "★") {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorites",
                            tint = if (isHighlighted) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isHighlighted) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
