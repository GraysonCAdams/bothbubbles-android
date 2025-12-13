package com.bothbubbles.ui.chat.composer.drawing

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Represents a text overlay item on the image.
 */
data class TextOverlayItem(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var position: Offset,
    var color: Color = Color.White,
    var fontSize: Float = 24f,
    var hasBackground: Boolean = true
)

/**
 * State holder for text overlays.
 */
class TextOverlayState {
    val items = mutableStateListOf<TextOverlayItem>()
    var selectedItemId by mutableStateOf<String?>(null)

    fun addItem(text: String, position: Offset): TextOverlayItem {
        val item = TextOverlayItem(
            text = text,
            position = position
        )
        items.add(item)
        selectedItemId = item.id
        return item
    }

    fun removeItem(id: String) {
        items.removeAll { it.id == id }
        if (selectedItemId == id) {
            selectedItemId = null
        }
    }

    fun updateItem(id: String, update: TextOverlayItem.() -> Unit) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = items[index]
            item.update()
            items[index] = item.copy()
        }
    }

    fun selectItem(id: String?) {
        selectedItemId = id
    }

    val selectedItem: TextOverlayItem?
        get() = items.find { it.id == selectedItemId }

    val hasItems: Boolean
        get() = items.isNotEmpty()
}

/**
 * Remember a TextOverlayState instance.
 */
@Composable
fun rememberTextOverlayState(): TextOverlayState {
    return remember { TextOverlayState() }
}

/**
 * A layer that displays and allows interaction with text overlays.
 *
 * @param textOverlayState State holder for text overlays
 * @param onTapToAdd Callback when user taps to add text at a position
 * @param modifier Modifier for the layer
 */
@Composable
fun TextOverlayLayer(
    textOverlayState: TextOverlayState,
    onTapToAdd: ((Offset) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(onTapToAdd) {
                if (onTapToAdd != null) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Deselect current item or add new one
                            if (textOverlayState.selectedItemId != null) {
                                textOverlayState.selectItem(null)
                            } else {
                                onTapToAdd(offset)
                            }
                        }
                    )
                }
            }
    ) {
        textOverlayState.items.forEach { item ->
            DraggableTextItem(
                item = item,
                isSelected = item.id == textOverlayState.selectedItemId,
                onSelect = { textOverlayState.selectItem(item.id) },
                onPositionChange = { newPosition ->
                    textOverlayState.updateItem(item.id) {
                        position = newPosition
                    }
                },
                onDelete = { textOverlayState.removeItem(item.id) }
            )
        }
    }
}

/**
 * A single draggable text item.
 */
@Composable
private fun DraggableTextItem(
    item: TextOverlayItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPositionChange: (Offset) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(item.position.x) }
    var offsetY by remember { mutableFloatStateOf(item.position.y) }

    LaunchedEffect(item.position) {
        offsetX = item.position.x
        offsetY = item.position.y
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    },
                    onDragEnd = {
                        onPositionChange(Offset(offsetX, offsetY))
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onSelect() })
            }
    ) {
        // Selection border
        val borderColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "borderColor"
        )

        Box(
            modifier = Modifier
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            // Text content
            Surface(
                color = if (item.hasBackground)
                    Color.Black.copy(alpha = 0.6f)
                else
                    Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = item.text,
                    color = item.color,
                    fontSize = item.fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Delete button when selected
            if (isSelected) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 12.dp, y = (-12).dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Toolbar for text overlay controls.
 */
@Composable
fun TextOverlayToolbar(
    textOverlayState: TextOverlayState,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedItem = textOverlayState.selectedItem

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
            // Add text button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Text Overlay",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Button(onClick = onAddText) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Text")
                }
            }

            // Selected item controls
            if (selectedItem != null) {
                Spacer(Modifier.height(12.dp))

                // Color picker for text
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selectedItem.color == color) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                                .clickable {
                                    textOverlayState.updateItem(selectedItem.id) {
                                        this.color = color
                                    }
                                }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Font size controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FormatSize,
                        contentDescription = "Font size",
                        tint = MaterialTheme.colorScheme.onSurface
                    )

                    FontSizes.forEach { size ->
                        Surface(
                            onClick = {
                                textOverlayState.updateItem(selectedItem.id) {
                                    fontSize = size.second
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedItem.fontSize == size.second)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = size.first,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedItem.fontSize == size.second)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Background toggle
                Surface(
                    onClick = {
                        textOverlayState.updateItem(selectedItem.id) {
                            hasBackground = !hasBackground
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedItem.hasBackground)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (selectedItem.hasBackground) "Background: On" else "Background: Off",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedItem.hasBackground)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dialog for adding new text overlay.
 */
@Composable
fun AddTextDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    if (!visible) return

    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Add Text",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Enter text...") },
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (text.isNotBlank()) {
                                onAdd(text)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

/**
 * Available text colors.
 */
private val TextColors = listOf(
    Color.White,
    Color.Black,
    Color.Red,
    Color(0xFFFF9800), // Orange
    Color.Yellow,
    Color(0xFF4CAF50), // Green
    Color(0xFF2196F3), // Blue
    Color(0xFF9C27B0), // Purple
)

/**
 * Font size options (display name, actual size).
 */
private val FontSizes = listOf(
    "S" to 16f,
    "M" to 24f,
    "L" to 32f,
    "XL" to 48f
)

/**
 * Render text overlays to a bitmap.
 */
fun TextOverlayState.renderToBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    items.forEach { item ->
        // Draw background if enabled
        if (item.hasBackground) {
            val bgPaint = Paint().apply {
                color = android.graphics.Color.argb(153, 0, 0, 0) // 60% black
                style = Paint.Style.FILL
            }
            val textPaint = Paint().apply {
                textSize = item.fontSize * 2.5f // Scale for bitmap
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val textWidth = textPaint.measureText(item.text)
            val padding = 16f
            canvas.drawRoundRect(
                item.position.x,
                item.position.y,
                item.position.x + textWidth + padding * 2,
                item.position.y + item.fontSize * 2.5f + padding,
                8f, 8f,
                bgPaint
            )
        }

        // Draw text
        val textPaint = Paint().apply {
            color = item.color.toArgb()
            textSize = item.fontSize * 2.5f // Scale for bitmap
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText(
            item.text,
            item.position.x + 16f,
            item.position.y + item.fontSize * 2.5f,
            textPaint
        )
    }

    return bitmap
}
