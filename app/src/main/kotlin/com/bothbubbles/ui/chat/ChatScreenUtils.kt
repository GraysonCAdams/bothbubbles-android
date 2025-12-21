package com.bothbubbles.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bothbubbles.ui.components.message.MessageGroupPosition
import com.bothbubbles.ui.components.message.MessageUiModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Time threshold for grouping consecutive messages (2 minutes).
 * Messages from the same sender within this window will be visually grouped.
 */
internal const val GROUP_TIME_THRESHOLD_MS = 2 * 60 * 1000L // 2 minutes

/**
 * Information about an attachment (file size, video duration, etc.)
 */
internal data class AttachmentInfo(
    val sizeBytes: Long,
    val formattedSize: String,
    val isVideo: Boolean,
    val isImage: Boolean,
    val isVLocation: Boolean = false,
    val durationMs: Long? = null,
    val durationFormatted: String? = null
)

/**
 * Get attachment info from a URI (file size, video duration, etc.)
 */
internal fun getAttachmentInfo(context: Context, uri: Uri): AttachmentInfo {
    var sizeBytes = 0L
    var isVideo = false
    var isImage = false
    var isVLocation = false
    var durationMs: Long? = null
    var displayName: String? = null

    Log.d("AttachmentInfo", "getAttachmentInfo called with uri: $uri")

    try {
        // Get MIME type
        val mimeType = context.contentResolver.getType(uri)
        isVideo = mimeType?.startsWith("video/") == true
        isImage = mimeType?.startsWith("image/") == true

        // Check for vLocation by MIME type
        if (mimeType == "text/x-vlocation") {
            isVLocation = true
        }

        // Get file size and display name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex)
                }
            }
        }

        // Also check for vLocation by file extension (.loc.vcf)
        // Simply check the full URI string which always contains the filename
        if (!isVLocation) {
            val uriString = uri.toString().lowercase()
            Log.d("AttachmentInfo", "Checking URI string for vLocation: $uriString")
            if (uriString.contains(".loc.vcf") || uriString.contains("-cl.loc")) {
                Log.d("AttachmentInfo", "vLocation detected via URI string!")
                isVLocation = true
            }
        }
        Log.d("AttachmentInfo", "Final isVLocation: $isVLocation, displayName: $displayName")

        // Get video duration if it's a video
        if (isVideo) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull()
                retriever.release()
            } catch (e: Exception) {
                // Ignore errors getting video duration
            }
        }
    } catch (e: Exception) {
        // Ignore errors
    }

    return AttachmentInfo(
        sizeBytes = sizeBytes,
        formattedSize = formatFileSize(sizeBytes),
        isVideo = isVideo,
        isImage = isImage,
        isVLocation = isVLocation,
        durationMs = durationMs,
        durationFormatted = durationMs?.let { formatDuration(it) }
    )
}

/**
 * Format file size for display (e.g., "1.5 MB")
 */
internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Format duration in milliseconds to a readable string (e.g., "1:30")
 */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Starts voice memo recording using MediaRecorder.
 * Creates a temporary file and configures the recorder for audio capture.
 */
internal fun startVoiceMemoRecording(
    context: Context,
    enableNoiseCancellation: Boolean = true,
    onRecorderCreated: (MediaRecorder, File) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val outputFile = File(
            context.cacheDir,
            "voice_memo_${System.currentTimeMillis()}.m4a"
        )

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            // Use VOICE_RECOGNITION for built-in noise suppression when enabled
            setAudioSource(
                if (enableNoiseCancellation) MediaRecorder.AudioSource.VOICE_RECOGNITION
                else MediaRecorder.AudioSource.MIC
            )
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        onRecorderCreated(recorder, outputFile)
    } catch (e: Exception) {
        onError("Failed to start recording: ${e.message}")
    }
}

/**
 * Determines if a time separator should be shown between two messages.
 * Shows separator if there's a gap of 15+ minutes between messages.
 */
internal fun shouldShowTimeSeparator(currentTimestamp: Long, previousTimestamp: Long): Boolean {
    val gapMillis = currentTimestamp - previousTimestamp
    val gapMinutes = TimeUnit.MILLISECONDS.toMinutes(gapMillis)
    return gapMinutes >= 15
}

// Thread-safe DateTimeFormatters (replaces SimpleDateFormat)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val DAY_OF_WEEK_FORMAT = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

/**
 * Formats a timestamp for the centered time separator.
 * Uses relative formatting like "Today 2:30 PM", "Yesterday", "Monday", or full date.
 */
internal fun formatTimeSeparator(timestamp: Long): String {
    val messageDate = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }

    val instant = Instant.ofEpochMilli(timestamp)
    val zonedDateTime = instant.atZone(ZoneId.systemDefault())

    return when {
        isSameDay(messageDate, today) -> {
            "Today ${TIME_FORMAT.format(zonedDateTime)}"
        }
        isSameDay(messageDate, yesterday) -> {
            "Yesterday ${TIME_FORMAT.format(zonedDateTime)}"
        }
        messageDate.after(weekAgo) -> {
            "${DAY_OF_WEEK_FORMAT.format(zonedDateTime)} ${TIME_FORMAT.format(zonedDateTime)}"
        }
        else -> {
            "${DATE_FORMAT.format(zonedDateTime)} ${TIME_FORMAT.format(zonedDateTime)}"
        }
    }
}

/**
 * Checks if two Calendar instances represent the same day.
 */
internal fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

/**
 * Calculates the group position for a message based on adjacent messages.
 * Messages are grouped when they:
 * - Share the same splitBatchId (composed together, e.g. text + attachments)
 * - OR are from the same sender AND within the time threshold
 * - Are not reaction messages
 *
 * In reversed layout (newest at index 0):
 * - Lower index = newer message (appears lower on screen)
 * - Higher index = older message (appears higher on screen)
 *
 * @return The MessageGroupPosition for visual bubble styling
 */
internal fun calculateGroupPosition(
    messages: List<MessageUiModel>,
    index: Int,
    message: MessageUiModel
): MessageGroupPosition {
    // Reaction messages are always single (they're typically hidden anyway)
    if (message.isReaction) {
        return MessageGroupPosition.SINGLE
    }

    // Get adjacent non-reaction messages
    val previousMessage = messages.getOrNull(index - 1)?.takeIf { !it.isReaction }
    val nextMessage = messages.getOrNull(index + 1)?.takeIf { !it.isReaction }

    // Check if messages share a splitBatchId (composed together as text + attachments)
    val sameBatchAsBelow = message.splitBatchId != null &&
            previousMessage?.splitBatchId == message.splitBatchId
    val sameBatchAsAbove = message.splitBatchId != null &&
            nextMessage?.splitBatchId == message.splitBatchId

    // Check if this message groups with the message below it (visually)
    // In reversed layout, index - 1 is the newer message that appears below
    // Split batch messages are ALWAYS grouped together regardless of time
    val groupsWithBelow = sameBatchAsBelow || (previousMessage?.let { prev ->
        prev.isFromMe == message.isFromMe &&
                kotlin.math.abs(message.dateCreated - prev.dateCreated) <= GROUP_TIME_THRESHOLD_MS
    } ?: false)

    // Check if this message groups with the message above it (visually)
    // In reversed layout, index + 1 is the older message that appears above
    val groupsWithAbove = sameBatchAsAbove || (nextMessage?.let { next ->
        next.isFromMe == message.isFromMe &&
                kotlin.math.abs(next.dateCreated - message.dateCreated) <= GROUP_TIME_THRESHOLD_MS
    } ?: false)

    return when {
        !groupsWithAbove && !groupsWithBelow -> MessageGroupPosition.SINGLE
        !groupsWithAbove && groupsWithBelow -> MessageGroupPosition.FIRST  // Top of visual group
        groupsWithAbove && groupsWithBelow -> MessageGroupPosition.MIDDLE
        groupsWithAbove && !groupsWithBelow -> MessageGroupPosition.LAST   // Bottom of visual group
        else -> MessageGroupPosition.SINGLE
    }
}
