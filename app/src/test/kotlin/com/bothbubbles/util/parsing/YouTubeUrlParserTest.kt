package com.bothbubbles.util.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlParserTest {

    @Test
    fun `parseUrl parses standard watch URL`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNotNull(result)
        assertEquals("dQw4w9WgXcQ", result?.videoId)
        assertEquals(url, result?.originalUrl)
    }

    @Test
    fun `parseUrl parses short URL`() {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNotNull(result)
        assertEquals("dQw4w9WgXcQ", result?.videoId)
    }

    @Test
    fun `parseUrl parses embed URL`() {
        val url = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNotNull(result)
        assertEquals("dQw4w9WgXcQ", result?.videoId)
    }

    @Test
    fun `parseUrl parses shorts URL`() {
        val url = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNotNull(result)
        assertEquals("dQw4w9WgXcQ", result?.videoId)
        assertTrue(result?.isShort == true)
    }

    @Test
    fun `parseUrl parses timestamp`() {
        val url = "https://youtu.be/dQw4w9WgXcQ?t=1m30s"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNotNull(result)
        assertEquals(90, result?.startTimeSeconds)
    }

    @Test
    fun `parseUrl returns null for non-YouTube URL`() {
        val url = "https://www.google.com"
        val result = YouTubeUrlParser.parseUrl(url)
        assertNull(result)
    }
}
