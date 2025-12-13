package com.bothbubbles.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * Preview functions for MessageBubble component.
 */

@Preview(showBackground = true, name = "Incoming Message")
@Composable
private fun MessageBubbleIncomingPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Outgoing Message")
@Composable
private fun MessageBubbleOutgoingPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessageFromMe,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Long Message")
@Composable
private fun MessageBubbleLongPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleLongMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(
    showBackground = true,
    name = "Dark Mode",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MessageBubbleDarkPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper(darkTheme = true) {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "SMS Message")
@Composable
private fun MessageBubbleSmsPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleSmsMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Emoji Only")
@Composable
private fun MessageBubbleEmojiOnlyPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleEmojiOnlyMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Reply Message")
@Composable
private fun MessageBubbleReplyPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleReplyMessage,
            onLongPress = {},
            onMediaClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Failed Message")
@Composable
private fun MessageBubbleFailedPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleFailedMessage,
            onLongPress = {},
            onMediaClick = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, name = "Group - First")
@Composable
private fun MessageBubbleGroupFirstPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.FIRST
        )
    }
}

@Preview(showBackground = true, name = "Group - Middle")
@Composable
private fun MessageBubbleGroupMiddlePreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.MIDDLE
        )
    }
}

@Preview(showBackground = true, name = "Group - Last")
@Composable
private fun MessageBubbleGroupLastPreview() {
    com.bothbubbles.ui.preview.PreviewWrapper {
        MessageBubble(
            message = com.bothbubbles.ui.preview.PreviewData.sampleMessage,
            onLongPress = {},
            onMediaClick = {},
            groupPosition = MessageGroupPosition.LAST
        )
    }
}
