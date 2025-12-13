package com.bothbubbles.services.sms

import java.io.ByteArrayOutputStream

/**
 * Handles MMS content type encoding and well-known content type mapping.
 * Implements WSP content type encoding per OMA-TS-MMS_ENC-V1_3.
 */
object MmsContentTypeHandler {

    // Well-known content types (WSP)
    const val CT_APPLICATION_MULTIPART_MIXED = 0x23
    const val CT_APPLICATION_MULTIPART_RELATED = 0x33

    // Content-Type parameter tokens
    const val PARAM_TYPE = 0x09
    const val PARAM_START = 0x0A

    /**
     * Get well-known content type token for common MIME types
     */
    fun getWellKnownContentType(contentType: String): Int? {
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

    /**
     * Write content type as token (short form)
     */
    fun writeContentTypeToken(output: ByteArrayOutputStream, token: Int) {
        output.write(token or 0x80) // Short form
    }

    /**
     * Write content type (either as token or text)
     */
    fun writeContentTypeText(output: ByteArrayOutputStream, contentType: String) {
        // Check for well-known content type
        val token = getWellKnownContentType(contentType)
        if (token != null) {
            output.write(token or 0x80)
        } else {
            // Write as text
            MmsPduEncodingHelpers.writeTextString(output, contentType)
        }
    }

    /**
     * Write multipart content type header with parameters
     */
    fun writeMultipartContentType(
        pdu: ByteArrayOutputStream,
        hasAttachments: Boolean
    ) {
        pdu.write(MmsPduHeaders.HEADER_CONTENT_TYPE)

        val contentTypeData = ByteArrayOutputStream()

        if (hasAttachments) {
            // multipart/related with start and type parameters
            writeContentTypeToken(contentTypeData, CT_APPLICATION_MULTIPART_RELATED)

            // Type parameter (for related)
            contentTypeData.write(PARAM_TYPE)
            MmsPduEncodingHelpers.writeTextString(contentTypeData, "application/smil")

            // Start parameter (content-id of root)
            contentTypeData.write(PARAM_START)
            MmsPduEncodingHelpers.writeTextString(contentTypeData, "<smil>")
        } else {
            // multipart/mixed
            writeContentTypeToken(contentTypeData, CT_APPLICATION_MULTIPART_MIXED)
        }

        // Write the length-prefixed content type
        val ctBytes = contentTypeData.toByteArray()
        MmsPduEncodingHelpers.writeValueLength(pdu, ctBytes.size)
        pdu.write(ctBytes)
    }
}
