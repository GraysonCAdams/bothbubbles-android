package com.bothbubbles.services.sms

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * MMS PDU header constants and builder functions.
 * Based on OMA-TS-MMS_ENC-V1_3 specification.
 */
object MmsPduHeaders {

    // MMS PDU header field IDs
    const val HEADER_MESSAGE_TYPE = 0x8C
    const val HEADER_TRANSACTION_ID = 0x98
    const val HEADER_MMS_VERSION = 0x8D
    const val HEADER_FROM = 0x89
    const val HEADER_TO = 0x97
    const val HEADER_CC = 0x82
    const val HEADER_BCC = 0x81
    const val HEADER_SUBJECT = 0x96
    const val HEADER_DATE = 0x85
    const val HEADER_CONTENT_TYPE = 0x84
    const val HEADER_EXPIRY = 0x88
    const val HEADER_PRIORITY = 0x8F
    const val HEADER_DELIVERY_REPORT = 0x86
    const val HEADER_READ_REPORT = 0x90

    // Message types
    const val MESSAGE_TYPE_SEND_REQ = 0x80

    // MMS versions
    const val MMS_VERSION_1_0 = 0x90
    const val MMS_VERSION_1_1 = 0x91
    const val MMS_VERSION_1_2 = 0x92
    const val MMS_VERSION_1_3 = 0x93

    /**
     * MMS priority levels
     */
    enum class Priority(val value: Int) {
        LOW(0x80),
        NORMAL(0x81),
        HIGH(0x82)
    }

    /**
     * Generate a unique transaction ID
     */
    fun generateTransactionId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(12)
    }

    /**
     * Write all MMS headers to the PDU output stream
     */
    fun writeHeaders(
        pdu: ByteArrayOutputStream,
        recipients: List<String>,
        subject: String?,
        requestDeliveryReport: Boolean,
        requestReadReport: Boolean,
        priority: Priority
    ) {
        // Message type: m-send-req
        pdu.write(HEADER_MESSAGE_TYPE)
        pdu.write(MESSAGE_TYPE_SEND_REQ)

        // Transaction ID (unique identifier)
        pdu.write(HEADER_TRANSACTION_ID)
        MmsPduEncodingHelpers.writeTextString(pdu, generateTransactionId())

        // MMS Version: 1.3
        pdu.write(HEADER_MMS_VERSION)
        pdu.write(MMS_VERSION_1_3)

        // Date
        pdu.write(HEADER_DATE)
        MmsPduEncodingHelpers.writeLongInteger(pdu, System.currentTimeMillis() / 1000)

        // From (insert-address-token - device fills this in)
        pdu.write(HEADER_FROM)
        pdu.write(0x01) // Value length
        pdu.write(0x81) // Insert-address-token

        // To recipients
        recipients.forEach { recipient ->
            pdu.write(HEADER_TO)
            MmsPduEncodingHelpers.writeEncodedAddress(pdu, recipient)
        }

        // Subject (if present)
        if (!subject.isNullOrBlank()) {
            pdu.write(HEADER_SUBJECT)
            MmsPduEncodingHelpers.writeEncodedString(pdu, subject)
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
}
