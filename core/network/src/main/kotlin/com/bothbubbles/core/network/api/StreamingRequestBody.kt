package com.bothbubbles.core.network.api

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException

/**
 * A RequestBody that streams content from a File without loading it entirely into memory.
 *
 * Used for uploading compressed images/videos where the compressed output is a temp file.
 * Streams in chunks to maintain constant memory usage regardless of file size.
 *
 * @param file The file to stream from
 * @param contentType The MIME type of the content
 * @param onProgress Optional callback for upload progress (bytesWritten, totalBytes)
 */
class FileStreamingRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun isOneShot(): Boolean = true

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val totalBytes = file.length()
        var bytesWritten = 0L

        file.inputStream().use { inputStream ->
            val source = inputStream.source()
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (source.read(okio.Buffer(), BUFFER_SIZE.toLong()).also {
                bytesRead = it.toInt()
            } != -1L && bytesRead > 0) {
                sink.write(okio.Buffer().apply { write(buffer, 0, bytesRead) }, bytesRead.toLong())
                bytesWritten += bytesRead
                onProgress?.invoke(bytesWritten, totalBytes)
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192 // 8KB chunks
    }
}

/**
 * A RequestBody that streams content from a Uri without loading it entirely into memory.
 *
 * Used for uploading attachments directly from content:// URIs (PDFs, documents,
 * uncompressed images/videos, etc.) without first copying them to a byte array.
 * Streams in chunks to maintain constant memory usage regardless of file size.
 *
 * @param contentResolver The ContentResolver to open the Uri
 * @param uri The content Uri to stream from
 * @param contentType The MIME type of the content
 * @param onProgress Optional callback for upload progress (bytesWritten, totalBytes)
 */
class UriStreamingRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentType: MediaType?,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {

    private val fileSize: Long by lazy {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = fileSize

    override fun isOneShot(): Boolean = true

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val totalBytes = fileSize
        var bytesWritten = 0L

        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open input stream for URI: $uri")

        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            while (stream.read(buffer).also { bytesRead = it } != -1) {
                sink.write(buffer, 0, bytesRead)
                bytesWritten += bytesRead
                if (totalBytes > 0) {
                    onProgress?.invoke(bytesWritten, totalBytes)
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192 // 8KB chunks
    }
}
