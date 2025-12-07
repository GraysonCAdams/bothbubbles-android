package com.bothbubbles.services.sms

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID

/**
 * Improved MMS PDU builder with proper MIME encoding, international address support,
 * attachment compression, and carrier-specific configuration.
 *
 * Based on OMA-TS-MMS_ENC-V1_3 specification.
 */
class MmsPduBuilder(private val context: Context) {

    companion object {
        private const val TAG = "MmsPduBuilder"

        // MMS PDU header field IDs
        private const val HEADER_MESSAGE_TYPE = 0x8C
        private const val HEADER_TRANSACTION_ID = 0x98
        private const val HEADER_MMS_VERSION = 0x8D
        private const val HEADER_FROM = 0x89
        private const val HEADER_TO = 0x97
        private const val HEADER_CC = 0x82
        private const val HEADER_BCC = 0x81
        private const val HEADER_SUBJECT = 0x96
        private const val HEADER_DATE = 0x85
        private const val HEADER_CONTENT_TYPE = 0x84
        private const val HEADER_EXPIRY = 0x88
        private const val HEADER_PRIORITY = 0x8F
        private const val HEADER_DELIVERY_REPORT = 0x86
        private const val HEADER_READ_REPORT = 0x90

        // Message types
        private const val MESSAGE_TYPE_SEND_REQ = 0x80

        // MMS versions
        private const val MMS_VERSION_1_0 = 0x90
        private const val MMS_VERSION_1_1 = 0x91
        private const val MMS_VERSION_1_2 = 0x92
        private const val MMS_VERSION_1_3 = 0x93

        // Address type suffix
        private const val ADDRESS_TYPE_PLMN = "/TYPE=PLMN"
        private const val ADDRESS_TYPE_IPV4 = "/TYPE=IPV4"
        private const val ADDRESS_TYPE_IPV6 = "/TYPE=IPV6"
        private const val ADDRESS_TYPE_EMAIL = "/TYPE=EMAIL"

        // Well-known content types (WSP)
        private const val CT_APPLICATION_MULTIPART_MIXED = 0x23
        private const val CT_APPLICATION_MULTIPART_RELATED = 0x33

        // Content-Type parameter tokens
        private const val PARAM_TYPE = 0x09
        private const val PARAM_START = 0x0A

        // Default size limits
        private const val DEFAULT_MAX_MESSAGE_SIZE = 300 * 1024 // 300KB
        private const val DEFAULT_MAX_IMAGE_SIZE = 250 * 1024 // 250KB for single image
        private const val MIN_JPEG_QUALITY = 30
        private const val DEFAULT_JPEG_QUALITY = 85

        // Image resizing thresholds
        private const val MAX_IMAGE_DIMENSION = 1280
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
        priority: Priority = Priority.NORMAL
    ): ByteArray {
        val maxSize = getCarrierMaxMmsSize(subscriptionId)
        Log.d(TAG, "Building MMS PDU with max size: $maxSize bytes")

        // Process attachments with compression if needed
        val processedAttachments = processAttachments(attachments, text, maxSize)

        val pdu = ByteArrayOutputStream()

        // === MMS Headers ===
        writeHeaders(
            pdu = pdu,
            recipients = recipients,
            subject = subject,
            hasAttachments = processedAttachments.isNotEmpty(),
            requestDeliveryReport = requestDeliveryReport,
            requestReadReport = requestReadReport,
            priority = priority
        )

        // === Content-Type header and body ===
        writeBody(pdu, text, processedAttachments)

        return pdu.toByteArray()
    }

    private fun writeHeaders(
        pdu: ByteArrayOutputStream,
        recipients: List<String>,
        subject: String?,
        hasAttachments: Boolean,
        requestDeliveryReport: Boolean,
        requestReadReport: Boolean,
        priority: Priority
    ) {
        // Message type: m-send-req
        pdu.write(HEADER_MESSAGE_TYPE)
        pdu.write(MESSAGE_TYPE_SEND_REQ)

        // Transaction ID (unique identifier)
        pdu.write(HEADER_TRANSACTION_ID)
        writeTextString(pdu, generateTransactionId())

        // MMS Version: 1.3
        pdu.write(HEADER_MMS_VERSION)
        pdu.write(MMS_VERSION_1_3)

        // Date
        pdu.write(HEADER_DATE)
        writeLongInteger(pdu, System.currentTimeMillis() / 1000)

        // From (insert-address-token - device fills this in)
        pdu.write(HEADER_FROM)
        pdu.write(0x01) // Value length
        pdu.write(0x81) // Insert-address-token

        // To recipients
        recipients.forEach { recipient ->
            pdu.write(HEADER_TO)
            writeEncodedAddress(pdu, recipient)
        }

        // Subject (if present)
        if (!subject.isNullOrBlank()) {
            pdu.write(HEADER_SUBJECT)
            writeEncodedString(pdu, subject)
        }

        // Priority
        if (priority != Priority.NORMAL) {
            pdu.write(HEADER_PRIORITY)
            pdu.write(priority.value)
        }

        // Delivery report
        if (requestDeliveryReport) {
            pdu.write(HEADER_DELIVERY_REPORT)
            pdu.write(0x80) // Yes
        }

        // Read report
        if (requestReadReport) {
            pdu.write(HEADER_READ_REPORT)
            pdu.write(0x80) // Yes
        }
    }

    private fun writeBody(
        pdu: ByteArrayOutputStream,
        text: String?,
        attachments: List<ProcessedAttachment>
    ) {
        val parts = mutableListOf<MmsPart>()

        // Add SMIL part for presentation (if we have attachments)
        if (attachments.isNotEmpty()) {
            val smil = generateSmil(text != null, attachments)
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
        writeMultipartContentType(pdu, parts, attachments.isNotEmpty())

        // Write number of parts
        writeUintvar(pdu, parts.size)

        // Write each part
        parts.forEach { part ->
            writePart(pdu, part)
        }
    }

    private fun writeMultipartContentType(
        pdu: ByteArrayOutputStream,
        parts: List<MmsPart>,
        hasAttachments: Boolean
    ) {
        pdu.write(HEADER_CONTENT_TYPE)

        val contentTypeData = ByteArrayOutputStream()

        if (hasAttachments) {
            // multipart/related with start and type parameters
            writeContentTypeToken(contentTypeData, CT_APPLICATION_MULTIPART_RELATED)

            // Type parameter (for related)
            contentTypeData.write(PARAM_TYPE)
            writeTextString(contentTypeData, "application/smil")

            // Start parameter (content-id of root)
            contentTypeData.write(PARAM_START)
            writeTextString(contentTypeData, "<smil>")
        } else {
            // multipart/mixed
            writeContentTypeToken(contentTypeData, CT_APPLICATION_MULTIPART_MIXED)
        }

        // Write the length-prefixed content type
        val ctBytes = contentTypeData.toByteArray()
        writeValueLength(pdu, ctBytes.size)
        pdu.write(ctBytes)
    }

    private fun writePart(pdu: ByteArrayOutputStream, part: MmsPart) {
        // Build headers
        val headers = ByteArrayOutputStream()

        // Content-Type
        headers.write(0x83 + 0x80) // Content-Type header (0x83 | 0x80 = short form)
        writeContentTypeText(headers, part.contentType)

        // Content-ID
        if (part.contentId.isNotEmpty()) {
            headers.write(0xC0) // Content-ID header (0x40 | 0x80)
            writeQuotedString(headers, part.contentId)
        }

        // Content-Location
        if (part.contentLocation.isNotEmpty()) {
            headers.write(0x8E) // Content-Location header (0x0E | 0x80)
            writeTextString(headers, part.contentLocation)
        }

        val headerBytes = headers.toByteArray()

        // Write headers length
        writeUintvar(pdu, headerBytes.size)

        // Write data length
        writeUintvar(pdu, part.data.size)

        // Write headers
        pdu.write(headerBytes)

        // Write data
        pdu.write(part.data)
    }

    /**
     * Write an encoded address with proper international character support
     */
    private fun writeEncodedAddress(output: ByteArrayOutputStream, address: String) {
        val normalized = normalizeAddress(address)
        val encoded = encodeAddressForMms(normalized)
        writeTextString(output, encoded)
    }

    /**
     * Normalize and encode phone number or email for MMS
     */
    private fun normalizeAddress(address: String): String {
        val trimmed = address.trim()

        // Check if it's an email
        if (trimmed.contains("@")) {
            return trimmed
        }

        // Phone number - normalize
        val digits = StringBuilder()
        var hasPlus = false

        for (c in trimmed) {
            when {
                c == '+' && digits.isEmpty() -> hasPlus = true
                c.isDigit() -> digits.append(c)
            }
        }

        return if (hasPlus) "+${digits}" else digits.toString()
    }

    /**
     * Add TYPE suffix for MMS address encoding
     */
    private fun encodeAddressForMms(address: String): String {
        return when {
            address.contains("@") -> "$address$ADDRESS_TYPE_EMAIL"
            address.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) ->
                "$address$ADDRESS_TYPE_IPV4"
            else -> "$address$ADDRESS_TYPE_PLMN"
        }
    }

    /**
     * Write encoded string with charset support for international characters
     */
    private fun writeEncodedString(output: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        // Check if ASCII-only
        val isAscii = bytes.all { it in 0..127 }

        if (isAscii) {
            // Simple text string
            writeTextString(output, text)
        } else {
            // Encoded string with charset
            val charset = 0x6A // UTF-8 MIB enum
            val valueLength = 1 + bytes.size + 1 // charset + text + null

            writeValueLength(output, valueLength)
            output.write(charset or 0x80) // Short integer form
            output.write(bytes)
            output.write(0x00) // Null terminator
        }
    }

    private fun writeTextString(output: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.write(0x00) // Null terminator
    }

    private fun writeQuotedString(output: ByteArrayOutputStream, text: String) {
        output.write(0x22) // Quote character
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.write(0x00) // Null terminator
    }

    private fun writeContentTypeToken(output: ByteArrayOutputStream, token: Int) {
        output.write(token or 0x80) // Short form
    }

    private fun writeContentTypeText(output: ByteArrayOutputStream, contentType: String) {
        // Check for well-known content type
        val token = getWellKnownContentType(contentType)
        if (token != null) {
            output.write(token or 0x80)
        } else {
            // Write as text
            writeTextString(output, contentType)
        }
    }

    private fun getWellKnownContentType(contentType: String): Int? {
        val baseType = contentType.split(";")[0].trim().lowercase()
        return when (baseType) {
            "text/plain" -> 0x03
            "text/html" -> 0x04
            "image/gif" -> 0x1D
            "image/jpeg" -> 0x1E
            "image/png" -> 0x1F
            "image/vnd.wap.wbmp" -> 0x21
            "application/smil" -> 0x33
            "audio/amr" -> 0x47
            "audio/mpeg" -> 0x46
            "video/3gpp" -> 0x43
            "video/mp4" -> 0x44
            else -> null
        }
    }

    private fun writeLongInteger(output: ByteArrayOutputStream, value: Long) {
        // Determine how many bytes needed
        val bytes = mutableListOf<Byte>()
        var v = value
        do {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        } while (v > 0)

        // Write length octet
        output.write(bytes.size)

        // Write value bytes
        bytes.forEach { output.write(it.toInt() and 0xFF) }
    }

    private fun writeValueLength(output: ByteArrayOutputStream, length: Int) {
        if (length <= 30) {
            output.write(length)
        } else {
            output.write(31) // Length-quote
            writeUintvar(output, length)
        }
    }

    private fun writeUintvar(output: ByteArrayOutputStream, value: Int) {
        val bytes = mutableListOf<Byte>()
        var v = value

        do {
            bytes.add(0, (v and 0x7F).toByte())
            v = v shr 7
        } while (v > 0)

        // Set continuation bits
        for (i in 0 until bytes.size - 1) {
            bytes[i] = (bytes[i].toInt() or 0x80).toByte()
        }

        bytes.forEach { output.write(it.toInt() and 0xFF) }
    }

    private fun generateTransactionId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(12)
    }

    /**
     * Generate SMIL presentation document
     */
    private fun generateSmil(hasText: Boolean, attachments: List<ProcessedAttachment>): String {
        val regions = StringBuilder()
        val body = StringBuilder()

        // Define regions
        regions.append("<region id=\"Text\" top=\"70%\" left=\"0%\" height=\"30%\" width=\"100%\" fit=\"scroll\"/>")
        regions.append("<region id=\"Image\" top=\"0%\" left=\"0%\" height=\"70%\" width=\"100%\" fit=\"meet\"/>")

        // Build body with parts
        body.append("<par dur=\"5000ms\">")

        if (hasText) {
            body.append("<text src=\"text_0.txt\" region=\"Text\"/>")
        }

        attachments.forEachIndexed { index, attachment ->
            val mediaType = when {
                attachment.mimeType.startsWith("image/") -> "img"
                attachment.mimeType.startsWith("video/") -> "video"
                attachment.mimeType.startsWith("audio/") -> "audio"
                else -> "ref"
            }
            body.append("<$mediaType src=\"${attachment.fileName}\" region=\"Image\"/>")
        }

        body.append("</par>")

        return """<?xml version="1.0" encoding="UTF-8"?>
<smil xmlns="http://www.w3.org/2001/SMIL20/Language">
<head>
<layout>
$regions
</layout>
</head>
<body>
$body
</body>
</smil>"""
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

        Log.d(TAG, "Available size for attachments: $availableSize bytes")

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
            val compressed = compressImage(data, targetSize, attachment.mimeType)
            if (compressed != null) {
                Log.d(TAG, "Compressed image from ${data.size} to ${compressed.size} bytes")
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
        Log.w(TAG, "Could not compress attachment ${attachment.fileName}, using original")
        return ProcessedAttachment(
            mimeType = attachment.mimeType,
            fileName = attachment.fileName,
            data = data
        )
    }

    /**
     * Compress image to target size using quality reduction and optional resizing
     */
    private fun compressImage(data: ByteArray, targetSize: Int, mimeType: String): ByteArray? {
        return try {
            var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null

            // Resize if dimensions are too large
            if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() /
                    maxOf(bitmap.width, bitmap.height).toFloat()
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (resized != bitmap) {
                    bitmap.recycle()
                    bitmap = resized
                }
                Log.d(TAG, "Resized image to ${newWidth}x${newHeight}")
            }

            // Binary search for optimal quality
            var quality = DEFAULT_JPEG_QUALITY
            var minQuality = MIN_JPEG_QUALITY
            var maxQuality = 100
            var result: ByteArray? = null

            while (minQuality <= maxQuality) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val compressed = output.toByteArray()

                if (compressed.size <= targetSize) {
                    result = compressed
                    minQuality = quality + 1
                } else {
                    maxQuality = quality - 1
                }
                quality = (minQuality + maxQuality) / 2
            }

            bitmap.recycle()

            // If still too large at minimum quality, try more aggressive resize
            if (result == null || result.size > targetSize) {
                return compressImageWithResize(data, targetSize)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image", e)
            null
        }
    }

    private fun compressImageWithResize(data: ByteArray, targetSize: Int): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)

            // Calculate sample size for aggressive downscaling
            var sampleSize = 2
            while (options.outWidth / sampleSize > 640 ||
                options.outHeight / sampleSize > 640
            ) {
                sampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, MIN_JPEG_QUALITY, output)
            bitmap.recycle()

            output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error with aggressive resize", e)
            null
        }
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

            Log.d(TAG, "Carrier max MMS size: $maxSize bytes")
            maxSize
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get carrier config, using default", e)
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

    /**
     * MMS priority levels
     */
    enum class Priority(val value: Int) {
        LOW(0x80),
        NORMAL(0x81),
        HIGH(0x82)
    }
}
