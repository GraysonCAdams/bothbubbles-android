package com.bothbubbles.services.sms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * PDU encoding utility functions for MMS message construction.
 * Implements WSP (Wireless Session Protocol) encoding methods per OMA-TS-MMS_ENC-V1_3.
 */
object MmsPduEncodingHelpers {

    /**
     * Write a text string with null terminator
     */
    fun writeTextString(output: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.write(0x00) // Null terminator
    }

    /**
     * Write a quoted string (starts with quote character)
     */
    fun writeQuotedString(output: ByteArrayOutputStream, text: String) {
        output.write(0x22) // Quote character
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.write(0x00) // Null terminator
    }

    /**
     * Write an encoded string with charset support for international characters
     */
    fun writeEncodedString(output: ByteArrayOutputStream, text: String) {
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

    /**
     * Write a long integer value
     */
    fun writeLongInteger(output: ByteArrayOutputStream, value: Long) {
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

    /**
     * Write a value length (used for variable-length fields)
     */
    fun writeValueLength(output: ByteArrayOutputStream, length: Int) {
        if (length <= 30) {
            output.write(length)
        } else {
            output.write(31) // Length-quote
            writeUintvar(output, length)
        }
    }

    /**
     * Write an unsigned integer variable (uintvar encoding)
     */
    fun writeUintvar(output: ByteArrayOutputStream, value: Int) {
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

    /**
     * Normalize phone number or email for MMS
     */
    fun normalizeAddress(address: String): String {
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
    fun encodeAddressForMms(address: String): String {
        return when {
            address.contains("@") -> "$address/TYPE=EMAIL"
            address.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) ->
                "$address/TYPE=IPV4"
            else -> "$address/TYPE=PLMN"
        }
    }

    /**
     * Write an encoded address with proper international character support
     */
    fun writeEncodedAddress(output: ByteArrayOutputStream, address: String) {
        val normalized = normalizeAddress(address)
        val encoded = encodeAddressForMms(normalized)
        writeTextString(output, encoded)
    }
}
