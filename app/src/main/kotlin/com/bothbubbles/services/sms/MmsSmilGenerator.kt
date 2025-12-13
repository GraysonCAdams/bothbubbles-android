package com.bothbubbles.services.sms

/**
 * Generates SMIL (Synchronized Multimedia Integration Language) documents for MMS presentation.
 * SMIL defines how text and media attachments are laid out in MMS viewers.
 */
object MmsSmilGenerator {

    /**
     * Processed attachment data for SMIL generation
     */
    data class AttachmentInfo(
        val mimeType: String,
        val fileName: String
    )

    /**
     * Generate SMIL presentation document for MMS
     *
     * @param hasText Whether the message includes text content
     * @param attachments List of attachments to include in presentation
     * @return XML string containing SMIL document
     */
    fun generateSmil(hasText: Boolean, attachments: List<AttachmentInfo>): String {
        val regions = StringBuilder()
        val body = StringBuilder()

        // Define regions for layout
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
}
