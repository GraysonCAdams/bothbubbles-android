package com.bothbubbles.ui.chat.composer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.bothbubbles.ui.chat.composer.drawing.AddTextDialog
import com.bothbubbles.ui.chat.composer.drawing.DrawingCanvas
import com.bothbubbles.ui.chat.composer.drawing.DrawingToolbar
import com.bothbubbles.ui.chat.composer.drawing.TextOverlayLayer
import com.bothbubbles.ui.chat.composer.drawing.TextOverlayToolbar
import com.bothbubbles.ui.chat.composer.drawing.rememberDrawingState
import com.bothbubbles.ui.chat.composer.drawing.rememberTextOverlayState
import com.bothbubbles.ui.chat.composer.drawing.renderToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Edit modes available in the attachment editor.
 */
enum class EditMode {
    ROTATE,
    DRAW,
    TEXT
}

/**
 * Advanced attachment edit screen with rotation, drawing, and text overlay.
 *
 * @param uri The URI of the image to edit
 * @param initialCaption The initial caption text
 * @param onSave Called when the user saves their edits
 * @param onCancel Called when the user cancels editing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentEditScreen(
    uri: Uri,
    initialCaption: String?,
    onSave: (editedUri: Uri?, caption: String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember { AttachmentEditor(context) }

    var rotation by remember { mutableFloatStateOf(0f) }
    var caption by remember { mutableStateOf(initialCaption ?: "") }
    var currentUri by remember { mutableStateOf(uri) }
    var isProcessing by remember { mutableStateOf(false) }

    // Edit mode selection
    var editMode by remember { mutableStateOf(EditMode.ROTATE) }

    // Drawing state
    val drawingState = rememberDrawingState()

    // Text overlay state
    val textOverlayState = rememberTextOverlayState()
    var showAddTextDialog by remember { mutableStateOf(false) }

    // Image size for rendering overlays
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    // Animate rotation for smooth visual feedback
    val animatedRotation by animateFloatAsState(
        targetValue = rotation,
        animationSpec = tween(200),
        label = "rotation"
    )

    // Helper to save all edits
    fun saveEdits() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            val finalUri = withContext(Dispatchers.IO) {
                try {
                    // Load original image
                    val inputStream = context.contentResolver.openInputStream(currentUri)
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (originalBitmap == null) {
                        return@withContext if (rotation != 0f) {
                            editor.rotateImage(currentUri, rotation)
                        } else null
                    }

                    // Create mutable bitmap for compositing
                    var resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

                    // Apply rotation if needed
                    if (rotation != 0f) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                        resultBitmap = Bitmap.createBitmap(
                            resultBitmap, 0, 0,
                            resultBitmap.width, resultBitmap.height,
                            matrix, true
                        )
                    }

                    // Apply drawing overlay if any
                    if (drawingState.hasDrawings && imageSize != IntSize.Zero) {
                        val drawingBitmap = drawingState.renderToBitmap(
                            resultBitmap.width,
                            resultBitmap.height
                        )
                        val canvas = Canvas(resultBitmap)
                        canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
                        drawingBitmap.recycle()
                    }

                    // Apply text overlays if any
                    if (textOverlayState.hasItems && imageSize != IntSize.Zero) {
                        val textBitmap = textOverlayState.renderToBitmap(
                            resultBitmap.width,
                            resultBitmap.height
                        )
                        val canvas = Canvas(resultBitmap)
                        canvas.drawBitmap(textBitmap, 0f, 0f, null)
                        textBitmap.recycle()
                    }

                    // Only save if there were changes
                    if (rotation != 0f || drawingState.hasDrawings || textOverlayState.hasItems) {
                        // Save to cache
                        val cacheDir = File(context.cacheDir, "edited_attachments").apply { mkdirs() }
                        val outputFile = File(cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(outputFile).use { out ->
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        originalBitmap.recycle()
                        resultBitmap.recycle()

                        FileProvider.getUriForFile(
                            context,
                            "com.bothbubbles.fileprovider",
                            outputFile
                        )
                    } else {
                        originalBitmap.recycle()
                        null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            onSave(finalUri, caption.ifBlank { null })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveEdits() },
                        enabled = !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Done")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            // Mode selector
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = editMode == EditMode.ROTATE,
                    onClick = { editMode = EditMode.ROTATE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    icon = {
                        Icon(
                            Icons.Default.RotateRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text("Rotate")
                }
                SegmentedButton(
                    selected = editMode == EditMode.DRAW,
                    onClick = { editMode = EditMode.DRAW },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    icon = {
                        Icon(
                            Icons.Default.Brush,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text("Draw")
                }
                SegmentedButton(
                    selected = editMode == EditMode.TEXT,
                    onClick = { editMode = EditMode.TEXT },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    icon = {
                        Icon(
                            Icons.Default.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text("Text")
                }
            }

            // Image preview with overlays
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onSizeChanged { imageSize = it },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .graphicsLayer { rotationZ = animatedRotation }
                ) {
                    AsyncImage(
                        model = currentUri,
                        contentDescription = "Image preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Drawing canvas overlay (always visible to preserve drawings)
                    if (editMode == EditMode.DRAW || drawingState.hasDrawings) {
                        DrawingCanvas(
                            drawingState = drawingState,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Text overlay layer (always visible to preserve text)
                    if (editMode == EditMode.TEXT || textOverlayState.hasItems) {
                        TextOverlayLayer(
                            textOverlayState = textOverlayState,
                            onTapToAdd = if (editMode == EditMode.TEXT) { position ->
                                showAddTextDialog = true
                            } else null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Mode-specific controls
            AnimatedVisibility(
                visible = editMode == EditMode.ROTATE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                RotateControls(
                    rotation = rotation,
                    onRotateLeft = { rotation -= 90f },
                    onRotateRight = { rotation += 90f },
                    enabled = !isProcessing
                )
            }

            AnimatedVisibility(
                visible = editMode == EditMode.DRAW,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                DrawingToolbar(
                    drawingState = drawingState,
                    onClear = { drawingState.clear() }
                )
            }

            AnimatedVisibility(
                visible = editMode == EditMode.TEXT,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TextOverlayToolbar(
                    textOverlayState = textOverlayState,
                    onAddText = { showAddTextDialog = true }
                )
            }

            // Caption input (always visible)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    label = { Text("Caption (optional)") },
                    placeholder = { Text("Add a caption...") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !isProcessing
                )
            }
        }
    }

    // Add text dialog
    AddTextDialog(
        visible = showAddTextDialog,
        onDismiss = { showAddTextDialog = false },
        onAdd = { text ->
            // Add text at center of image
            textOverlayState.addItem(
                text = text,
                position = Offset(
                    x = (imageSize.width / 2f) - 50f,
                    y = (imageSize.height / 2f) - 20f
                )
            )
            showAddTextDialog = false
        }
    )
}

/**
 * Rotation controls component.
 */
@Composable
private fun RotateControls(
    rotation: Float,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onRotateLeft,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.RotateLeft,
                    contentDescription = "Rotate left"
                )
                Spacer(Modifier.width(8.dp))
                Text("Rotate Left")
            }

            Spacer(Modifier.width(16.dp))

            FilledTonalButton(
                onClick = onRotateRight,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.RotateRight,
                    contentDescription = "Rotate right"
                )
                Spacer(Modifier.width(8.dp))
                Text("Rotate Right")
            }
        }
    }
}

/**
 * Bottom sheet version for quick edits on attachment previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentEditSheet(
    visible: Boolean,
    uri: Uri?,
    initialCaption: String?,
    onDismiss: () -> Unit,
    onSave: (editedUri: Uri?, caption: String?) -> Unit
) {
    if (!visible || uri == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        val context = LocalContext.current
        val editor = remember { AttachmentEditor(context) }

        var rotation by remember { mutableFloatStateOf(0f) }
        var caption by remember { mutableStateOf(initialCaption ?: "") }
        var isProcessing by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit Attachment",
                    style = MaterialTheme.typography.titleLarge
                )

                TextButton(
                    onClick = {
                        if (!isProcessing) {
                            isProcessing = true
                            val rotatedUri = if (rotation != 0f) {
                                editor.rotateImage(uri, rotation)
                            } else null
                            onSave(rotatedUri, caption.ifBlank { null })
                            onDismiss()
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text("Done")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotation },
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(Modifier.height(16.dp))

            // Rotation controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { rotation -= 90f }) {
                    Icon(Icons.Default.RotateLeft, "Rotate left")
                }
                IconButton(onClick = { rotation += 90f }) {
                    Icon(Icons.Default.RotateRight, "Rotate right")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Caption
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Caption") },
                placeholder = { Text("Add a caption...") },
                singleLine = true
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
