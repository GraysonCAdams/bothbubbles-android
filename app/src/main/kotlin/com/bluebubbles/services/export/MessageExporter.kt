package com.bluebubbles.services.export

import java.io.File

/**
 * Interface for message export format implementations.
 * Allows extensibility for future formats.
 */
interface MessageExporter {

    /** The format this exporter handles */
    val format: ExportFormat

    /** File extension for this format (without dot) */
    val fileExtension: String

    /** MIME type for this format */
    val mimeType: String

    /**
     * Export chats to a file
     *
     * @param chats List of chats with their messages to export
     * @param style The visual style to use
     * @param outputFile The file to write to
     * @return Result indicating success or failure
     */
    suspend fun export(
        chats: List<ExportableChat>,
        style: ExportStyle,
        outputFile: File
    ): Result<File>
}
