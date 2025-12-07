package com.bluebubbles.services.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import com.bluebubbles.data.local.db.dao.AttachmentDao
import com.bluebubbles.data.local.db.dao.ChatDao
import com.bluebubbles.data.local.db.dao.HandleDao
import com.bluebubbles.data.local.db.dao.MessageDao
import com.bluebubbles.data.local.db.entity.MessageSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val handleDao: HandleDao,
    private val attachmentDao: AttachmentDao,
    private val htmlExporter: HtmlExporter,
    private val pdfExporter: PdfExporter
) {
    companion object {
        private const val TAG = "MessageExportService"
        private const val EXPORT_DIR = "BlueBubbles"
    }

    private val _exportProgress = MutableStateFlow<ExportProgress>(ExportProgress.Idle)
    val exportProgress: StateFlow<ExportProgress> = _exportProgress.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    /**
     * Export messages based on the provided configuration.
     * Returns a Flow that emits progress updates.
     */
    fun export(config: ExportConfig): Flow<ExportProgress> = flow {
        try {
            _exportProgress.value = ExportProgress.Generating("Preparing export...")
            emit(ExportProgress.Generating("Preparing export..."))

            // Get list of chat GUIDs to export
            val chatGuids = if (config.chatGuids.isEmpty()) {
                chatDao.getAllChatGuids()
            } else {
                config.chatGuids
            }

            if (chatGuids.isEmpty()) {
                val error = ExportProgress.Error("No conversations to export")
                _exportProgress.value = error
                emit(error)
                return@flow
            }

            // Load all chat data
            val exportableChats = mutableListOf<ExportableChat>()
            var totalMessages = 0

            chatGuids.forEachIndexed { index, chatGuid ->
                currentCoroutineContext().ensureActive()

                val chat = chatDao.getChatByGuid(chatGuid)
                if (chat == null) return@forEachIndexed

                val loadingProgress = ExportProgress.Loading(
                    chatName = chat.displayName ?: "Unknown",
                    chatIndex = index + 1,
                    totalChats = chatGuids.size
                )
                _exportProgress.value = loadingProgress
                emit(loadingProgress)

                // Load messages for this chat with date filtering
                val messages = loadMessagesForChat(chatGuid, config.startDate, config.endDate)

                if (messages.isNotEmpty()) {
                    // Get handle cache for sender names
                    val handleCache = mutableMapOf<Long, String>()

                    val exportableMessages = messages.mapNotNull { message ->
                        // Skip reactions and group events
                        if (message.isReaction || message.isGroupEvent) return@mapNotNull null

                        // Get sender name
                        val senderName = if (message.isFromMe) {
                            "Me"
                        } else {
                            message.handleId?.let { handleId ->
                                handleCache.getOrPut(handleId) {
                                    handleDao.getHandleById(handleId)?.let { handle ->
                                        handle.cachedDisplayName
                                            ?: handle.inferredName
                                            ?: handle.formattedAddress
                                            ?: handle.address
                                    } ?: "Unknown"
                                }
                            } ?: "Unknown"
                        }

                        // Get attachment names
                        val attachmentNames = if (message.hasAttachments) {
                            attachmentDao.getAttachmentsForMessage(message.guid)
                                .mapNotNull { it.transferName ?: it.mimeType }
                        } else {
                            emptyList()
                        }

                        // Determine message source display name
                        val messageSourceDisplay = when (message.messageSource) {
                            MessageSource.IMESSAGE.name -> "iMessage"
                            MessageSource.SERVER_SMS.name, MessageSource.LOCAL_SMS.name -> "SMS"
                            MessageSource.LOCAL_MMS.name -> "MMS"
                            else -> "iMessage"
                        }

                        ExportableMessage(
                            guid = message.guid,
                            chatGuid = message.chatGuid,
                            text = message.fullText,
                            dateCreated = message.dateCreated,
                            isFromMe = message.isFromMe,
                            senderName = senderName,
                            attachmentNames = attachmentNames,
                            messageSource = messageSourceDisplay
                        )
                    }.sortedBy { it.dateCreated }

                    if (exportableMessages.isNotEmpty()) {
                        exportableChats.add(
                            ExportableChat(
                                guid = chatGuid,
                                displayName = chat.displayName ?: "Unknown",
                                isGroup = chat.isGroup,
                                messages = exportableMessages
                            )
                        )
                        totalMessages += exportableMessages.size
                    }
                }
            }

            if (exportableChats.isEmpty()) {
                val error = ExportProgress.Error("No messages found to export")
                _exportProgress.value = error
                emit(error)
                return@flow
            }

            currentCoroutineContext().ensureActive()

            // Generate the export file
            val generatingProgress = ExportProgress.Generating("Creating ${config.format.name} file...")
            _exportProgress.value = generatingProgress
            emit(generatingProgress)

            val exporter = when (config.format) {
                ExportFormat.HTML -> htmlExporter
                ExportFormat.PDF -> pdfExporter
            }

            // Create temp output file
            val timestamp = dateFormat.format(Date())
            val fileName = "BlueBubbles_Export_$timestamp.${exporter.fileExtension}"
            val tempFile = File(context.cacheDir, fileName)

            val exportResult = exporter.export(exportableChats, config.style, tempFile)

            if (exportResult.isFailure) {
                val error = ExportProgress.Error(
                    exportResult.exceptionOrNull()?.message ?: "Export failed"
                )
                _exportProgress.value = error
                emit(error)
                return@flow
            }

            currentCoroutineContext().ensureActive()

            // Save to Downloads
            val savingProgress = ExportProgress.Saving(fileName)
            _exportProgress.value = savingProgress
            emit(savingProgress)

            val savedUri = saveToDownloads(tempFile, fileName, exporter.mimeType)

            // Clean up temp file
            tempFile.delete()

            if (savedUri != null) {
                val completeProgress = ExportProgress.Complete(
                    filePath = savedUri,
                    fileName = fileName,
                    messageCount = totalMessages,
                    chatCount = exportableChats.size
                )
                _exportProgress.value = completeProgress
                emit(completeProgress)
            } else {
                val error = ExportProgress.Error("Failed to save file to Downloads")
                _exportProgress.value = error
                emit(error)
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            _exportProgress.value = ExportProgress.Cancelled
            emit(ExportProgress.Cancelled)
            throw e
        } catch (e: Exception) {
            val error = ExportProgress.Error(e.message ?: "Export failed")
            _exportProgress.value = error
            emit(error)
        }
    }

    private suspend fun loadMessagesForChat(
        chatGuid: String,
        startDate: Long?,
        endDate: Long?
    ): List<com.bluebubbles.data.local.db.entity.MessageEntity> {
        // Load all messages for the chat, then filter by date
        // This is simpler than adding a new DAO method
        val allMessages = mutableListOf<com.bluebubbles.data.local.db.entity.MessageEntity>()
        var offset = 0
        val pageSize = 500

        while (true) {
            val batch = messageDao.getMessagesForChat(chatGuid, pageSize, offset)
            if (batch.isEmpty()) break
            allMessages.addAll(batch)
            offset += pageSize
        }

        // Filter by date range
        return allMessages.filter { message ->
            val afterStart = startDate == null || message.dateCreated >= startDate
            val beforeEnd = endDate == null || message.dateCreated <= endDate
            afterStart && beforeEnd
        }
    }

    private suspend fun saveToDownloads(file: File, fileName: String, mimeType: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use MediaStore for Android 10+
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIR")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext null

                    resolver.openOutputStream(uri)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // Mark as complete
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    uri.toString()
                } else {
                    // Legacy storage for older Android
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val exportDir = File(downloadsDir, EXPORT_DIR)
                    exportDir.mkdirs()

                    val destFile = File(exportDir, fileName)
                    file.copyTo(destFile, overwrite = true)
                    destFile.absolutePath
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun resetProgress() {
        _exportProgress.value = ExportProgress.Idle
    }
}
