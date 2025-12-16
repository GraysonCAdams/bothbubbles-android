# Media Service

## Purpose

Media processing and caching services for attachments. Handles image/video compression, thumbnail generation, and download queue management.

## Files

| File | Description |
|------|-------------|
| `AttachmentDownloadQueue.kt` | Prioritized download queue for attachments |
| `AttachmentLimitsProvider.kt` | Attachment size and type limits |
| `AttachmentPreloader.kt` | Preload attachments for smooth scrolling |
| `ExoPlayerPool.kt` | Pool of ExoPlayer instances for video playback |
| `ImageCompressor.kt` | Compress images before sending |
| `ThumbnailManager.kt` | Generate and cache thumbnails |
| `VideoCompressor.kt` | Compress videos before sending |
| `VideoThumbnailCache.kt` | Cache video frame thumbnails |

## Architecture

```
Media Processing Pipeline:

Outgoing:
User Selection → ImageCompressor/VideoCompressor
              → AttachmentLimitsProvider (check limits)
              → Upload to server

Incoming:
Server Notification → AttachmentDownloadQueue
                   → ThumbnailManager (generate thumbnail)
                   → Cache locally
```

## Required Patterns

### Download Queue

```kotlin
class AttachmentDownloadQueue @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val queue = PriorityQueue<DownloadRequest>(compareByDescending { it.priority })

    suspend fun enqueue(attachment: Attachment, priority: Priority) {
        queue.add(DownloadRequest(attachment, priority))
        processQueue()
    }

    private suspend fun processQueue() = withContext(ioDispatcher) {
        while (queue.isNotEmpty()) {
            val request = queue.poll()
            downloadAttachment(request.attachment)
        }
    }
}
```

### Image Compression

```kotlin
class ImageCompressor @Inject constructor(
    private val attachmentPreferences: AttachmentPreferences
) {
    suspend fun compress(uri: Uri, quality: AttachmentQuality): ByteArray {
        val bitmap = decodeBitmap(uri)
        val maxSize = when (quality) {
            AttachmentQuality.ORIGINAL -> Int.MAX_VALUE
            AttachmentQuality.HIGH -> 2048
            AttachmentQuality.MEDIUM -> 1024
            AttachmentQuality.LOW -> 512
        }
        return compressToJpeg(bitmap.scaledToFit(maxSize), quality.jpegQuality)
    }
}
```

### ExoPlayer Pool

```kotlin
class ExoPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pool = mutableListOf<ExoPlayer>()

    fun acquire(): ExoPlayer {
        return pool.removeFirstOrNull() ?: createPlayer()
    }

    fun release(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        pool.add(player)
    }
}
```

## Best Practices

1. Compress media before sending (save bandwidth)
2. Generate thumbnails for preview (don't load full images)
3. Pool ExoPlayer instances (expensive to create)
4. Prioritize visible attachments in download queue
5. Preload attachments for smooth scrolling
6. Respect user quality preferences
