# In-App Camera

## Purpose

In-app camera screen for capturing photos and videos to send in messages.

## Files

| File | Description |
|------|-------------|
| `InAppCameraScreen.kt` | Camera capture UI |

## Architecture

```
Camera Flow:

User taps camera → InAppCameraScreen
                → CameraX preview
                → Capture photo/video
                → Return URI to calling screen
```

## Required Patterns

### Camera Screen

```kotlin
@Composable
fun InAppCameraScreen(
    onCapture: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    imageCapture = ImageCapture.Builder().build()

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )

                    preview.setSurfaceProvider(surfaceProvider)
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )

    CameraControls(
        onCapture = {
            takePhoto(imageCapture, context) { uri ->
                onCapture(uri)
            }
        },
        onDismiss = onDismiss
    )
}
```

## Best Practices

1. Use CameraX for simplified camera access
2. Handle camera permissions
3. Support front/back camera switching
4. Support photo and video capture
5. Handle lifecycle properly (unbind on dispose)
