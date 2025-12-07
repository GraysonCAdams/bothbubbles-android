package com.bothbubbles.services.sms

import android.util.Log
import java.io.ByteArrayInputStream

/**
 * Parser for MMS PDU data extracted from WAP Push notifications.
 *
 * MMS uses a WAP Push delivery mechanism. When an MMS is sent, the recipient receives
 * a WAP Push containing an MMS notification indication (m-notification-ind) PDU.
 * This PDU contains metadata about the MMS including:
 * - Content location (URL to download the actual MMS)
 * - Sender address
 * - Subject
 * - Message size
 * - Expiry time
 *
 * Reference: OMA-TS-MMS_ENC-V1_3 (MMS Encoding specification)
 */
object MmsPduParser {

    private const val TAG = "MmsPduParser"

    // MMS PDU header field IDs (from OMA MMS specification)
    private const val MMS_MESSAGE_TYPE = 0x8C
    private const val MMS_TRANSACTION_ID = 0x98
    private const val MMS_VERSION = 0x8D
    private const val MMS_FROM = 0x89
    private const val MMS_SUBJECT = 0x96
    private const val MMS_MESSAGE_CLASS = 0x8A
    private const val MMS_MESSAGE_SIZE = 0x8E
    private const val MMS_EXPIRY = 0x88
    private const val MMS_CONTENT_LOCATION = 0x83
    private const val MMS_CONTENT_TYPE = 0x84

    // MMS message types
    private const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82

    // Address type tokens
    private const val ADDRESS_PRESENT_TOKEN = 0x80

    /**
     * Data class containing parsed MMS notification info
     */
    data class MmsNotificationInfo(
        val transactionId: String?,
        val contentLocation: String?,
        val from: String?,
        val subject: String?,
        val messageSize: Long?,
        val expiry: Long?,
        val messageClass: String?,
        val isValid: Boolean
    )

    /**
     * Parse an MMS notification indication PDU from WAP Push data.
     *
     * @param pdu The raw PDU bytes from the WAP Push intent
     * @return Parsed notification info, or null if parsing fails
     */
    fun parseNotificationInd(pdu: ByteArray?): MmsNotificationInfo? {
        if (pdu == null || pdu.isEmpty()) {
            Log.w(TAG, "Empty PDU data")
            return null
        }

        return try {
            val stream = ByteArrayInputStream(pdu)
            parsePdu(stream)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MMS PDU", e)
            null
        }
    }

    private fun parsePdu(stream: ByteArrayInputStream): MmsNotificationInfo {
        var transactionId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        var subject: String? = null
        var messageSize: Long? = null
        var expiry: Long? = null
        var messageClass: String? = null
        var messageType: Int? = null

        while (stream.available() > 0) {
            val header = stream.read()
            if (header == -1) break

            when (header) {
                MMS_MESSAGE_TYPE -> {
                    messageType = stream.read()
                    Log.d(TAG, "Message type: $messageType")
                }

                MMS_TRANSACTION_ID -> {
                    transactionId = readTextString(stream)
                    Log.d(TAG, "Transaction ID: $transactionId")
                }

                MMS_VERSION -> {
                    // Skip version byte
                    stream.read()
                }

                MMS_FROM -> {
                    from = readEncodedAddress(stream)
                    Log.d(TAG, "From: $from")
                }

                MMS_SUBJECT -> {
                    subject = readEncodedString(stream)
                    Log.d(TAG, "Subject: $subject")
                }

                MMS_MESSAGE_CLASS -> {
                    messageClass = readMessageClass(stream)
                }

                MMS_MESSAGE_SIZE -> {
                    messageSize = readLongInteger(stream)
                    Log.d(TAG, "Message size: $messageSize")
                }

                MMS_EXPIRY -> {
                    expiry = readValueLengthLongInteger(stream)
                    Log.d(TAG, "Expiry: $expiry")
                }

                MMS_CONTENT_LOCATION -> {
                    contentLocation = readTextString(stream)
                    Log.d(TAG, "Content location: $contentLocation")
                }

                MMS_CONTENT_TYPE -> {
                    // Content type is more complex, skip for notification parsing
                    skipContentType(stream)
                }

                else -> {
                    // Unknown header, try to skip
                    if (header and 0x80 != 0) {
                        // Short header, value follows
                        skipUnknownValue(stream)
                    }
                }
            }
        }

        val isValid = messageType == MESSAGE_TYPE_NOTIFICATION_IND && contentLocation != null

        return MmsNotificationInfo(
            transactionId = transactionId,
            contentLocation = contentLocation,
            from = from,
            subject = subject,
            messageSize = messageSize,
            expiry = expiry,
            messageClass = messageClass,
            isValid = isValid
        )
    }

    /**
     * Read a text string (null-terminated)
     */
    private fun readTextString(stream: ByteArrayInputStream): String {
        val bytes = mutableListOf<Byte>()

        // Check for quote character (0x7F) which indicates quoted string
        val first = stream.read()
        if (first == 0x7F) {
            // Quoted string, read until null
        } else if (first != -1 && first != 0) {
            bytes.add(first.toByte())
        }

        while (true) {
            val b = stream.read()
            if (b == -1 || b == 0) break
            bytes.add(b.toByte())
        }

        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Read an encoded string (with charset prefix)
     */
    private fun readEncodedString(stream: ByteArrayInputStream): String {
        val first = stream.read()
        if (first == -1) return ""

        return if (first <= 31) {
            // Value-length encoded string
            val length = first
            val charset = stream.read() // Charset byte
            val textBytes = ByteArray(length - 1)
            stream.read(textBytes)

            // Try to decode with proper charset
            try {
                String(textBytes, getCharset(charset))
            } catch (e: Exception) {
                String(textBytes, Charsets.UTF_8)
            }
        } else {
            // Text string
            val bytes = mutableListOf(first.toByte())
            while (true) {
                val b = stream.read()
                if (b == -1 || b == 0) break
                bytes.add(b.toByte())
            }
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }

    /**
     * Read an encoded address (From field)
     */
    private fun readEncodedAddress(stream: ByteArrayInputStream): String? {
        val valueLength = stream.read()
        if (valueLength <= 0) return null

        val addressType = stream.read()

        return when (addressType) {
            ADDRESS_PRESENT_TOKEN -> {
                // Address is present, read as encoded string
                val addressBytes = ByteArray(valueLength - 1)
                stream.read(addressBytes)
                parseAddressString(String(addressBytes, Charsets.UTF_8).trimEnd('\u0000'))
            }

            else -> {
                // Skip remaining bytes
                stream.skip((valueLength - 1).toLong())
                null
            }
        }
    }

    /**
     * Parse and clean up an MMS address string.
     * Addresses can be in format: "+15551234567/TYPE=PLMN" or "user@domain.com/TYPE=EMAIL"
     */
    private fun parseAddressString(address: String): String {
        // Remove /TYPE=XXX suffix if present
        val typeIndex = address.indexOf("/TYPE=")
        return if (typeIndex > 0) {
            address.substring(0, typeIndex)
        } else {
            address
        }
    }

    /**
     * Read message class value
     */
    private fun readMessageClass(stream: ByteArrayInputStream): String {
        return when (stream.read()) {
            0x80 -> "personal"
            0x81 -> "advertisement"
            0x82 -> "informational"
            0x83 -> "auto"
            else -> "unknown"
        }
    }

    /**
     * Read a long integer (variable length)
     */
    private fun readLongInteger(stream: ByteArrayInputStream): Long {
        val first = stream.read()
        if (first == -1) return 0L

        return if (first <= 30) {
            // Short-length followed by that many octets
            var value = 0L
            repeat(first) {
                val b = stream.read()
                if (b != -1) {
                    value = (value shl 8) or (b.toLong() and 0xFF)
                }
            }
            value
        } else {
            // Single octet value
            (first and 0x7F).toLong()
        }
    }

    /**
     * Read value-length prefixed long integer
     */
    private fun readValueLengthLongInteger(stream: ByteArrayInputStream): Long {
        val length = stream.read()
        if (length <= 0) return 0L

        // First byte after length indicates absolute or relative token
        val token = stream.read()

        var value = 0L
        repeat(length - 1) {
            val b = stream.read()
            if (b != -1) {
                value = (value shl 8) or (b.toLong() and 0xFF)
            }
        }

        return when (token) {
            0x80 -> value // Absolute time (seconds since epoch)
            0x81 -> System.currentTimeMillis() / 1000 + value // Relative time (seconds from now)
            else -> value
        }
    }

    /**
     * Skip content type parsing (complex structure)
     */
    private fun skipContentType(stream: ByteArrayInputStream) {
        val first = stream.read()
        if (first <= 31) {
            // Value-length followed by content
            stream.skip(first.toLong())
        }
        // Otherwise it's a constrained-media token, already consumed
    }

    /**
     * Skip an unknown value
     */
    private fun skipUnknownValue(stream: ByteArrayInputStream) {
        val value = stream.read()
        if (value <= 31) {
            // Length-prefixed value
            stream.skip(value.toLong())
        }
        // Otherwise single byte value, already consumed
    }

    /**
     * Get charset from MMS charset code
     */
    private fun getCharset(code: Int): java.nio.charset.Charset {
        return when (code) {
            0x6A, 106 -> Charsets.UTF_8
            0x03, 3 -> Charsets.US_ASCII
            0x04, 4 -> Charsets.ISO_8859_1
            else -> Charsets.UTF_8
        }
    }
}
