package com.bothbubbles.ui.components.message

import com.bothbubbles.ui.util.toStable
import com.bothbubbles.util.parsing.DetectedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSegmentParserTest {

    private fun createMessage(text: String?): MessageUiModel {
        return MessageUiModel(
            guid = "guid",
            text = text,
            dateCreated = 0L,
            formattedTime = "now",
            isFromMe = true,
            isSent = true,
            isDelivered = true,
            isRead = true,
            hasError = false,
            isReaction = false,
            attachments = emptyList<AttachmentUiModel>().toStable(),
            senderName = "Me",
            messageSource = "IMESSAGE"
        )
    }

    @Test
    fun `parse creates YouTubeVideoSegment for YouTube URL`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val message = createMessage(url)
        val detectedUrl = DetectedUrl(0, url.length, url, url, "youtube.com")
        
        val segments = MessageSegmentParser.parse(message, detectedUrl)
        
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.YouTubeVideoSegment)
        val segment = segments[0] as MessageSegment.YouTubeVideoSegment
        assertEquals("dQw4w9WgXcQ", segment.videoId)
    }

    @Test
    fun `parse creates LinkPreviewSegment for non-YouTube URL`() {
        val url = "https://www.google.com"
        val message = createMessage(url)
        val detectedUrl = DetectedUrl(0, url.length, url, url, "google.com")
        
        val segments = MessageSegmentParser.parse(message, detectedUrl)
        
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.LinkPreviewSegment)
    }
    
    @Test
    fun `parse separates text and YouTube video`() {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        val text = "Check this out: $url"
        val message = createMessage(text)
        val detectedUrl = DetectedUrl(16, 16 + url.length, url, url, "youtu.be")
        
        val segments = MessageSegmentParser.parse(message, detectedUrl)
        
        assertEquals(2, segments.size)
        assertTrue(segments[0] is MessageSegment.TextSegment)
        assertEquals("Check this out:", (segments[0] as MessageSegment.TextSegment).text)
        assertTrue(segments[1] is MessageSegment.YouTubeVideoSegment)
    }
}
