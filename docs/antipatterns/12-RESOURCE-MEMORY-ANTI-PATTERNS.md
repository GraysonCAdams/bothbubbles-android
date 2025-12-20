# Resource and Memory Management Anti-Patterns

**Scope:** Streams, bitmaps, native resources, caches

---

## High Severity Issues

### 1. HTTP Response Not Closed (Connection Leak)

**Location:** `services/linkpreview/OpenGraphParser.kt` (Lines 29-90)

**Issue:**
```kotlin
val response = httpClient.newCall(request).execute()

if (!response.isSuccessful) {
    Timber.w("HTTP error ${response.code} for $url")
    return LinkMetadataResult.Error("HTTP ${response.code}")  // Response leaked!
}

val html = response.body?.string()
if (html.isNullOrBlank()) {
    return LinkMetadataResult.Error("Empty response body")  // Response leaked!
}
```

**Problem:** Response object never closed on early returns, causing connection pool exhaustion.

**Fix:**
```kotlin
httpClient.newCall(request).execute().use { response ->
    if (!response.isSuccessful) {
        return LinkMetadataResult.Error("HTTP ${response.code}")
    }
    val html = response.body?.string()
    // ...
}
```

---

### 2. InputStream Not Closed on Exception

**Locations:**
- `ui/chat/composer/AttachmentEditor.kt` (Lines 104-106, 131-133)
- `ui/chat/composer/AttachmentEditScreen.kt` (Lines 149-151)

**Issue:**
```kotlin
val inputStream = context.contentResolver.openInputStream(uri) ?: return null
val bitmap = BitmapFactory.decodeStream(inputStream)  // Can throw!
inputStream.close()  // Only reached if decodeStream succeeds
```

**Problem:** If `BitmapFactory.decodeStream()` throws, inputStream leaks.

**Fix:**
```kotlin
val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
    BitmapFactory.decodeStream(inputStream)
} ?: return null
```

---

### 3. Paint Objects Created Every Frame

**Location:** `ui/chat/composer/drawing/DrawingCanvas.kt` (Lines 147-185, 205-226)

**Issue:**
```kotlin
drawingState.strokes.forEach { stroke ->
    val paint = Paint().apply {  // NEW Paint EVERY iteration, EVERY frame
        style = Paint.Style.STROKE
        strokeWidth = stroke.strokeWidth
        color = stroke.color
    }
    canvas.drawPath(stroke.path.asAndroidPath(), paint)
}
```

**Problem:**
- Creates new Paint objects on every recomposition
- In drawing canvas, this happens every frame during active drawing
- Causes GC pressure and jank

**Fix:**
```kotlin
class DrawingState {
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    fun getConfiguredPaint(stroke: Stroke): Paint {
        return strokePaint.apply {
            strokeWidth = stroke.strokeWidth
            color = stroke.color
        }
    }
}
```

---

### 4. Bitmap Recycle Gaps in AvatarGenerator

**Location:** `util/AvatarGenerator.kt` (Lines 198-246)

**Issue:**
```kotlin
val sourceBitmap = BitmapFactory.decodeStream(stream)
if (sourceBitmap == null) return null

val croppedBitmap = centerCropToSquare(sourceBitmap)
// If centerCropToSquare() throws, sourceBitmap leaks!

val circularBitmap = createCircularBitmap(croppedBitmap)
// Multiple intermediate bitmaps may leak on exception
```

**Problem:** Exception during multi-step bitmap processing leaves intermediate bitmaps unrecycled.

**Fix:**
```kotlin
var sourceBitmap: Bitmap? = null
var croppedBitmap: Bitmap? = null
try {
    sourceBitmap = BitmapFactory.decodeStream(stream) ?: return null
    croppedBitmap = centerCropToSquare(sourceBitmap)
    return createCircularBitmap(croppedBitmap)
} finally {
    croppedBitmap?.recycle()
    if (croppedBitmap != sourceBitmap) sourceBitmap?.recycle()
}
```

---

### 5. Large MMS Attachment OOM Risk

**Location:** `services/export/SmsBackupService.kt` (Lines 301-309)

**Issue:**
```kotlin
val data = context.contentResolver.openInputStream(
    Uri.parse("content://mms/part/$partId")
)?.use { inputStream ->
    Base64.encodeToString(inputStream.readBytes(), Base64.NO_WRAP)  // ALL in RAM!
}
```

**Problem:** Reads entire attachment into memory for Base64 encoding. Large attachments (10MB+) can cause OOM.

**Fix:**
```kotlin
// Stream-based Base64 encoding
val data = context.contentResolver.openInputStream(uri)?.use { input ->
    ByteArrayOutputStream().use { baos ->
        val encoder = Base64OutputStream(baos, Base64.NO_WRAP)
        input.copyTo(encoder, bufferSize = 8192)
        encoder.close()
        baos.toString("UTF-8")
    }
}
```

---

## Medium Severity Issues

### 6. Native Canvas Not Released

**Location:** `ui/chat/composer/drawing/DrawingCanvas.kt` (Line 207)

**Issue:**
```kotlin
fun DrawingState.renderToBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)  // Native canvas
    // canvas is never explicitly released
    return bitmap
}
```

**Problem:** Native Canvas object created but not explicitly released.

**Note:** Android typically handles this via GC, but explicit cleanup is better practice.

---

## Summary Table

| Issue | Severity | File | Type | Fix |
|-------|----------|------|------|-----|
| HTTP Response leak | HIGH | OpenGraphParser.kt | Network | Use `.use {}` |
| InputStream leak | HIGH | AttachmentEditor.kt | File | Use `.use {}` |
| InputStream leak | HIGH | AttachmentEditScreen.kt | File | Use `.use {}` |
| Paint over-allocation | HIGH | DrawingCanvas.kt | Composition | Cache Paint |
| Bitmap recycle gaps | MEDIUM | AvatarGenerator.kt | Memory | try-finally |
| MMS OOM risk | MEDIUM | SmsBackupService.kt | Memory | Stream encode |
| Canvas not released | LOW | DrawingCanvas.kt | Native | Explicit cleanup |

---

## Correctly Handled Patterns

- ExoPlayerPool properly releases players
- VideoThumbnailCache uses LRUCache with proper eviction
- Cursor management uses `.use {}` throughout
- VideoCompressor has proper try-finally cleanup
