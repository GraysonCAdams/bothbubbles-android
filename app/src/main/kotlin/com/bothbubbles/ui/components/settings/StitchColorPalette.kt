package com.bothbubbles.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Color palette for selecting Stitch bubble colors.
 *
 * Displays a grid of colors organized by hue with 5 shades each.
 * The current selection is indicated with a checkmark.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StitchColorPalette(
    currentColor: Color,
    defaultColor: Color,
    isUsingDefault: Boolean,
    onColorSelected: (Color) -> Unit,
    onResetToDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Current color preview
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(currentColor)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current Color",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (isUsingDefault) "Default" else "Custom",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Color palette grid
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PALETTE_COLORS.forEach { color ->
                    ColorSwatch(
                        color = color,
                        isSelected = colorMatches(currentColor, color),
                        onClick = { onColorSelected(color) }
                    )
                }
            }

            // Reset button
            if (!isUsingDefault) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onResetToDefault,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset to Default")
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                }
            )
            .clickable(
                role = Role.RadioButton,
                onClick = onClick
            )
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = getContrastColor(color),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Determines if two colors match within a small tolerance.
 */
private fun colorMatches(a: Color, b: Color): Boolean {
    val tolerance = 0.01f
    return kotlin.math.abs(a.red - b.red) < tolerance &&
           kotlin.math.abs(a.green - b.green) < tolerance &&
           kotlin.math.abs(a.blue - b.blue) < tolerance
}

/**
 * Returns a contrasting color (black or white) for visibility on the given background.
 */
private fun getContrastColor(background: Color): Color {
    // Calculate relative luminance
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.5f) Color.Black else Color.White
}

/**
 * Color palette: 7 hues with 5 shades each (35 colors total).
 * Colors are optimized for message bubble readability.
 */
private val PALETTE_COLORS = listOf(
    // Red shades
    Color(0xFFFFCDD2), Color(0xFFEF9A9A), Color(0xFFE57373), Color(0xFFEF5350), Color(0xFFE53935),
    // Orange shades
    Color(0xFFFFE0B2), Color(0xFFFFCC80), Color(0xFFFFB74D), Color(0xFFFF9800), Color(0xFFF57C00),
    // Yellow shades
    Color(0xFFFFF9C4), Color(0xFFFFF59D), Color(0xFFFFF176), Color(0xFFFFEE58), Color(0xFFFDD835),
    // Green shades
    Color(0xFFC8E6C9), Color(0xFFA5D6A7), Color(0xFF81C784), Color(0xFF66BB6A), Color(0xFF43A047),
    // Blue shades
    Color(0xFFBBDEFB), Color(0xFF90CAF9), Color(0xFF64B5F6), Color(0xFF42A5F5), Color(0xFF1E88E5),
    // Purple shades
    Color(0xFFE1BEE7), Color(0xFFCE93D8), Color(0xFFBA68C8), Color(0xFFAB47BC), Color(0xFF8E24AA),
    // Pink shades
    Color(0xFFF8BBD0), Color(0xFFF48FB1), Color(0xFFF06292), Color(0xFFEC407A), Color(0xFFD81B60)
)
