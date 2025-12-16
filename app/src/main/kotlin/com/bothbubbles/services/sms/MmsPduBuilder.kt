package com.bothbubbles.services.sms

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Improved MMS PDU builder with proper MIME encoding, international address support,
 * attachment compression, and carrier-specific configuration.
 *
 * Based on OMA-TS-MMS_ENC-V1_3 specification.
 */
class MmsPduBuilder(private val context: Context) {

    companion object {
        // Default size limits
        private const val DEFAULT_MAX_MESSAGE_SIZE = 300 * 1024 // 300KB
        private const val DEFAULT_MAX_IMAGE_SIZE = 250 * 1024 // 250KB for single image
    }

    /**
     * Build MMS PDU with full MIME-compliant encoding
     */
    fun build(
        recipients: List<String>,
        text: String?,
        attachments: List<AttachmentData>,
        subject: String? = null,
        subscriptionId: Int = -1,
        requestDeliveryReport: Boolean = false,
        requestReadReport: Boolean = false,
        priority: MmsPduHeaders.Priority = MmsPduHeaders.Priority.NORMAL
    ): ByteArray {
        val maxSize = getCarrierMaxMmsSize(subscriptionId)
        Timber.d("Building MMS PDU with max size: $maxSize bytes")

        // Process attachments with compression if needed
        val processedAttachments = processAttachments(attachments, text, maxSize)

        val pdu = ByteArrayOutputStream()

        // === MMS Headers ===
        MmsPduHeaders.writeHeaders(
            pdu = pdu,
            recipients = recipients,
            subject = subject,
            requestDeliveryReport = requestDeliveryReport,
            requestReadReport = requestReadReport,
            priority = priority
        )

        // === Content-Type header and body ===
        writeBody(pdu, text, processedAttachments)

        return pdu.toByteArray()
    }

    private fun writeBody(
        pdu: ByteArrayOutputStream,
        text: String?,
        attachments: List<ProcessedAttachment>
    ) {
        val parts = mutableListOf<MmsPart>()

        // Add SMIL part for presentation (if we have attachments)
        if (attachments.isNotEmpty()) {
            val attachmentInfos = attachments.map {
                MmsSmilGenerator.AttachmentInfo(
                    mimeType = it.mimeType,
                    fileName = it.fileName
                )
            }
            val smil = MmsSmilGenerator.generateSmil(text != null, attachmentInfos)
            parts.add(
                MmsPart(
                    contentId = "<smil>",
                    contentType = "application/smil",
                    contentLocation = "smil.xml",
                    data = smil.toByteArray(Charsets.UTF_8)
                )
            )
        }

        // Add text part
        if (!text.isNullOrBlank()) {
            parts.add(
                MmsPart(
                    contentId = "<text_0>",
                    contentType = "text/plain; charset=utf-8",
                    contentLocation = "text_0.txt",
                    data = text.toByteArray(Charsets.UTF_8)
                )
            )
        }

        // Add attachment parts
        attachments.forEachIndexed { index, attachment ->
            parts.add(
                MmsPart(
                    contentId = "<attachment_$index>",
                    contentType = attachment.mimeType,
                    contentLocation = attachment.fileName,
                    data = attachment.data
                )
            )
        }

        // Write Content-Type header with multipart type
        MmsContentTypeHandler.writeMultipartContentType(pdu, attachments.isNotEmpty())

        // Write number of parts
        MmsPduEncodingHelpers.writeUintvar(pdu, parts.size)

        // Write each part
        parts.forEach { part ->
            writePart(pdu, part)
        }
    }

    private fun writePart(pdu: ByteArrayOutputStream, part: MmsPart) {
        // Build headers
        val headers = ByteArrayOutputStream()

        // Content-Type
        headers.write(0x83 + 0x80) // Content-Type header (0x83 | 0x80 = short form)
        MmsContentTypeHandler.writeContentTypeText(headers, part.contentType)

        // Content-ID
        if (part.contentId.isNotEmpty()) {
            headers.write(0xC0) // Content-ID header (0x40 | 0x80)
            MmsPduEncodingHelpers.writeQuotedString(headers, part.contentId)
        }

        // Content-Location
        if (part.contentLocation.isNotEmpty()) {
            headers.write(0x8E) // Content-Location header (0x0E | 0x80)
            MmsPduEncodingHelpers.writeTextString(headers, part.contentLocation)
        }

        val headerBytes = headers.toByteArray()

        // Write headers length
        MmsPduEncodingHelpers.writeUintvar(pdu, headerBytes.size)

        // Write data length
        MmsPduEncodingHelpers.writeUintvar(pdu, part.data.size)

        // Write headers
        pdu.write(headerBytes)

        // Write data
        pdu.write(part.data)
    }

    /**
     * Process attachments with compression if needed to fit within carrier limits
     */
    private fun processAttachments(
        attachments: List<AttachmentData>,
        text: String?,
        maxSize: Int
    ): List<ProcessedAttachment> {
        if (attachments.isEmpty()) return emptyList()

        // Calculate overhead (headers, SMIL, text)
        val textSize = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val smilOverhead = 1024 // Approximate SMIL size
        val headerOverhead = 2048 // Approximate header overhead
        val availableSize = maxSize - textSize - smilOverhead - headerOverhead

        Timber.d("Available size for attachments: $availableSize bytes")

        // Calculate target size per attachment
        val targetPerAttachment = (availableSize / attachments.size).coerceAtLeast(10 * 1024)

        return attachments.map { attachment ->
            processAttachment(attachment, targetPerAttachment)
        }
    }

    private fun processAttachment(attachment: AttachmentData, targetSize: Int): ProcessedAttachment {
        val data = attachment.data

        // Check if already under target size
        if (data.size <= targetSize) {
            return ProcessedAttachment(
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                data = data
            )
        }

        // Try to compress if it's an image
        if (attachment.mimeType.startsWith("image/") &&
            (attachment.mimeType.contains("jpeg") ||
                attachment.mimeType.contains("jpg") ||
                attachment.mimeType.contains("png"))
        ) {
            val compressed = MmsImageCompressor.compressImage(data, targetSize, attachment.mimeType)
            if (compressed != null) {
                Timber.d("Compressed image from ${data.size} to ${compressed.size} bytes")
                val newFileName = if (attachment.fileName.endsWith(".png")) {
                    attachment.fileName.replace(".png", ".jpg")
                } else {
                    attachment.fileName
                }
                return ProcessedAttachment(
                    mimeType = "image/jpeg",
                    fileName = newFileName,
                    data = compressed
                )
            }
        }

        // Return original if compression not possible
        Timber.w("Could not compress attachment ${attachment.fileName}, using original")
        return ProcessedAttachment(
            mimeType = attachment.mimeType,
            fileName = attachment.fileName,
            data = data
        )
    }

    /**
     * Get carrier's maximum MMS size from CarrierConfig
     */
    private fun getCarrierMaxMmsSize(subscriptionId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return DEFAULT_MAX_MESSAGE_SIZE
        }

        return try {
            val carrierConfigManager =
                context.getSystemService(Context.CARRIER_CONFIG_SERVICE) as? CarrierConfigManager
                    ?: return DEFAULT_MAX_MESSAGE_SIZE

            val subId = if (subscriptionId >= 0) {
                subscriptionId
            } else {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            }

            val config: PersistableBundle? = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                carrierConfigManager.getConfigForSubId(subId)
            } else {
                carrierConfigManager.config
            }

            val maxSize = config?.getInt(
                CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT,
                DEFAULT_MAX_MESSAGE_SIZE
            ) ?: DEFAULT_MAX_MESSAGE_SIZE

            Timber.d("Carrier max MMS size: $maxSize bytes")
            maxSize
        } catch (e: Exception) {
            Timber.w(e, "Failed to get carrier config, using default")
            DEFAULT_MAX_MESSAGE_SIZE
        }
    }

    /**
     * Attachment input data
     */
    data class AttachmentData(
        val mimeType: String,
        val fileName: String,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AttachmentData
            return fileName == other.fileName && mimeType == other.mimeType
        }

        override fun hashCode(): Int = fileName.hashCode()
    }

    /**
     * Processed attachment ready for PDU
     */
    private data class ProcessedAttachment(
        val mimeType: String,
        val fileName: String,
        val data: ByteArray
    )

    /**
     * MMS part for encoding
     */
    private data class MmsPart(
        val contentId: String,
        val contentType: String,
        val contentLocation: String,
        val data: ByteArray
    )
}
