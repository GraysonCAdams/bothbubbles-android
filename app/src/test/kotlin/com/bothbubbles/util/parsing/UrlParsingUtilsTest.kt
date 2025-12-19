package com.bothbubbles.util.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UrlParsingUtilsTest {

    @Test
    fun `getFirstUrl detects YouTube URL`() {
        val text = "Check this video https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = UrlParsingUtils.getFirstUrl(text)
        assertNotNull(result)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result?.url)
    }

    @Test
    fun `getFirstUrl detects short YouTube URL`() {
        val text = "Check this video https://youtu.be/dQw4w9WgXcQ"
        val result = UrlParsingUtils.getFirstUrl(text)
        assertNotNull(result)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", result?.url)
    }
}
