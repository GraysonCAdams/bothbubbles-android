package com.bothbubbles.ui.chat.composer.drawing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp

/**
 * Available colors for drawing.
 */
val DrawingColors = listOf(
    Color.Red,
    Color(0xFFFF9800), // Orange
    Color.Yellow,
    Color(0xFF4CAF50), // Green
    Color(0xFF2196F3), // Blue
    Color(0xFF9C27B0), // Purple
    Color(0xFFE91E63), // Pink
    Color.White,
    Color.Black
)

/**
 * Brush size presets.
 */
enum class BrushSize(val width: Float, val displayName: String) {
    THIN(4f, "Thin"),
    MEDIUM(8f, "Medium"),
    THICK(16f, "Thick"),
    VERY_THICK(24f, "Bold")
}

/**
 * Toolbar for drawing controls including color picker, brush size, eraser, and undo/redo.
 *
 * @param drawingState The state holder for drawing
 * @param onClear Callback to clear all drawings
 * @param modifier Modifier for the toolbar
 */
@Composable
fun DrawingToolbar(
    drawingState: DrawingState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showBrushSizeSlider by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top row: Undo, Redo, Clear, Eraser toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { drawingState.undo() },
                        enabled = drawingState.canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (drawingState.canUndo)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = { drawingState.redo() },
                        enabled = drawingState.canRedo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (drawingState.canRedo)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Eraser toggle
                    Surface(
                        onClick = { drawingState.isEraserMode = !drawingState.isEraserMode },
                        shape = RoundedCornerShape(8.dp),
                        color = if (drawingState.isEraserMode)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Eraser icon (represented as a square)
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        if (drawingState.isEraserMode)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Eraser",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (drawingState.isEraserMode)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(
                        onClick = onClear,
                        enabled = drawingState.hasDrawings
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear all",
                            tint = if (drawingState.hasDrawings)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Color picker row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrawingColors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        isSelected = drawingState.currentColor == color && !drawingState.isEraserMode,
                        onClick = {
                            drawingState.currentColor = color
                            drawingState.isEraserMode = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Brush size controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Brush,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Size",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Brush size presets
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrushSize.entries.forEach { size ->
                        BrushSizeButton(
                            size = size,
                            isSelected = drawingState.currentStrokeWidth == size.width,
                            color = drawingState.currentColor,
                            onClick = { drawingState.currentStrokeWidth = size.width }
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single color swatch button.
 */
@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val size by animateDpAsState(
        targetValue = if (isSelected) 36.dp else 28.dp,
        label = "swatchSize"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 0.dp,
        label = "borderWidth"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(
                width = borderWidth,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape
            )
            .then(
                if (color == Color.White) {
                    Modifier.border(1.dp, Color.LightGray, CircleShape)
                } else Modifier
            )
            .clickable(onClick = onClick)
    )
}

/**
 * A brush size preset button.
 */
@Composable
private fun BrushSizeButton(
    size: BrushSize,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.size(36.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp)
        ) {
            // Draw a circle representing the brush size
            Box(
                modifier = Modifier
                    .size((size.width / 1.5f).dp.coerceIn(4.dp, 20.dp))
                    .background(
                        if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape
                    )
            )
        }
    }
}
