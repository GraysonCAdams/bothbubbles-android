package com.bothbubbles.util.parsing

import android.os.Build
import android.text.Html

/**
 * Utility object for decoding HTML entities in text.
 *
 * This is needed because the BlueBubbles server sometimes sends message text
 * with HTML-encoded Unicode characters (e.g., &#x1d406; for styled/fancy text).
 */
object HtmlEntityDecoder {

    /**
     * Decodes HTML entities in the given text.
     *
     * Handles:
     * - Numeric character references: &#x1d406; (hex) and &#65; (decimal)
     * - Named entities: &amp;, &lt;, &gt;, &quot;, &nbsp;, etc.
     *
     * @param text The text containing HTML entities
     * @return The decoded text with entities converted to actual characters
     */
    fun decode(text: String?): String? {
        if (text.isNullOrEmpty()) return text

        // Quick check: if no & character, no entities to decode
        if (!text.contains('&')) return text

        return try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                Html.fromHtml(text).toString()
            }
        } catch (_: Exception) {
            // If HTML parsing fails for any reason, return original text
            text
        }
    }
}
