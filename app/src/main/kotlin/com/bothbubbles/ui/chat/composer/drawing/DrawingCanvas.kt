package com.bothbubbles.ui.chat.composer.drawing

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Represents a single drawing stroke with its properties.
 */
data class DrawingStroke(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false
)

/**
 * State holder for the drawing canvas.
 */
class DrawingState {
    val strokes = mutableStateListOf<DrawingStroke>()
    private val undoneStrokes = mutableStateListOf<DrawingStroke>()

    var currentColor by mutableStateOf(Color.Red)
    var currentStrokeWidth by mutableStateOf(8f)
    var isEraserMode by mutableStateOf(false)

    fun addStroke(stroke: DrawingStroke) {
        strokes.add(stroke)
        undoneStrokes.clear()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undoneStrokes.add(strokes.removeLast())
        }
    }

    fun redo() {
        if (undoneStrokes.isNotEmpty()) {
            strokes.add(undoneStrokes.removeLast())
        }
    }

    fun clear() {
        strokes.clear()
        undoneStrokes.clear()
    }

    val canUndo: Boolean
        get() = strokes.isNotEmpty()

    val canRedo: Boolean
        get() = undoneStrokes.isNotEmpty()

    val hasDrawings: Boolean
        get() = strokes.isNotEmpty()
}

/**
 * Remember a DrawingState instance.
 */
@Composable
fun rememberDrawingState(): DrawingState {
    return remember { DrawingState() }
}

/**
 * A canvas that supports touch-based drawing with customizable brush and eraser.
 *
 * @param drawingState State holder for strokes and drawing settings
 * @param modifier Modifier for the canvas container
 */
@Composable
fun DrawingCanvas(
    drawingState: DrawingState,
    modifier: Modifier = Modifier
) {
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var currentStartPoint by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(drawingState.isEraserMode, drawingState.currentColor, drawingState.currentStrokeWidth) {
                detectDragGestures(
                    onDragStart = { offset ->
                        currentPath = Path().apply {
                            moveTo(offset.x, offset.y)
                        }
                        currentStartPoint = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentPath?.lineTo(change.position.x, change.position.y)
                        currentPath = currentPath // Trigger recomposition
                    },
                    onDragEnd = {
                        currentPath?.let { path ->
                            drawingState.addStroke(
                                DrawingStroke(
                                    path = path,
                                    color = if (drawingState.isEraserMode) Color.Transparent else drawingState.currentColor,
                                    strokeWidth = drawingState.currentStrokeWidth,
                                    isEraser = drawingState.isEraserMode
                                )
                            )
                        }
                        currentPath = null
                        currentStartPoint = null
                    },
                    onDragCancel = {
                        currentPath = null
                        currentStartPoint = null
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw completed strokes
            drawingState.strokes.forEach { stroke ->
                if (stroke.isEraser) {
                    // For eraser, we draw with native canvas to use blend mode
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = Paint().apply {
                            style = Paint.Style.STROKE
                            strokeWidth = stroke.strokeWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                            isAntiAlias = true
                        }
                        drawPath(stroke.path.asAndroidPath(), paint)
                    }
                } else {
                    drawPath(
                        path = stroke.path,
                        color = stroke.color,
                        style = Stroke(
                            width = stroke.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // Draw current path being drawn
            currentPath?.let { path ->
                if (drawingState.isEraserMode) {
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = Paint().apply {
                            style = Paint.Style.STROKE
                            strokeWidth = drawingState.currentStrokeWidth
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            color = android.graphics.Color.GRAY
                            alpha = 128
                            isAntiAlias = true
                        }
                        drawPath(path.asAndroidPath(), paint)
                    }
                } else {
                    drawPath(
                        path = path,
                        color = drawingState.currentColor,
                        style = Stroke(
                            width = drawingState.currentStrokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

/**
 * Render all strokes to a bitmap for saving.
 */
fun DrawingState.renderToBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    strokes.forEach { stroke ->
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            if (stroke.isEraser) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                color = stroke.color.toArgb()
            }
        }
        canvas.drawPath(stroke.path.asAndroidPath(), paint)
    }

    return bitmap
}
