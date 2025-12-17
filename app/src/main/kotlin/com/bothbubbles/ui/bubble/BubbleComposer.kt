package com.bothbubbles.ui.bubble

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.chat.ChatSendMode
import com.bothbubbles.ui.chat.components.SendButton
import com.bothbubbles.ui.chat.composer.components.ComposerActionButtons
import com.bothbubbles.ui.chat.composer.components.ComposerMediaButtons
import com.bothbubbles.ui.chat.composer.components.ComposerTextField
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Chat composer for the bubble screen that mirrors the main app's composer.
 *
 * Reuses the same components as [com.bothbubbles.ui.chat.composer.ChatComposer]:
 * - [ComposerTextField] - Text input with consistent styling
 * - [ComposerActionButtons] - Add/attach button (opens expand action in bubble)
 * - [ComposerMediaButtons] - Camera, emoji, gallery buttons
 * - [SendButton] - Protocol-aware send button
 *
 * The bubble composer includes all visual features of the main composer but
 * delegates complex actions (attachment panels, camera capture) to the full app
 * via the expand button, keeping the bubble lightweight.
 *
 * @param text Current draft text
 * @param onTextChange Callback when text changes
 * @param onSendClick Callback when send button is clicked
 * @param onExpandClick Callback to open full app (for advanced features)
 * @param onMediaSelected Callback when user picks media from gallery
 * @param isSending Whether a message is currently being sent
 * @param isLocalSmsChat Whether this is a local SMS chat (affects send button color)
 * @param modifier Modifier for the composer container
 */
@Composable
fun BubbleComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onExpandClick: () -> Unit,
    onMediaSelected: (List<Uri>) -> Unit,
    isSending: Boolean,
    isLocalSmsChat: Boolean,
    modifier: Modifier = Modifier
) {
    val inputColors = BothBubblesTheme.bubbleColors
    val hasContent = text.isNotBlank()
    val sendMode = if (isLocalSmsChat) ChatSendMode.SMS else ChatSendMode.IMESSAGE

    // Photo picker launcher for gallery access
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onMediaSelected(uris)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = inputColors.inputBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text input field with add button and media buttons
            ComposerTextField(
                text = text,
                onTextChange = onTextChange,
                sendMode = sendMode,
                leadingContent = {
                    // Add button - opens full app for attachment panels
                    ComposerActionButtons(
                        isExpanded = false,
                        onClick = onExpandClick
                    )
                },
                trailingContent = {
                    // Camera, emoji, gallery buttons
                    ComposerMediaButtons(
                        showCamera = text.isBlank(),
                        onCameraClick = onExpandClick, // Camera opens full app
                        onEmojiClick = onExpandClick,  // Emoji opens full app
                        onImageClick = {
                            // Gallery picker works directly in bubble
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            )

            // Send button - only visible when there's content
            if (hasContent) {
                Spacer(modifier = Modifier.width(6.dp))

                SendButton(
                    onClick = onSendClick,
                    onLongClick = { /* No effects in bubble */ },
                    isSending = isSending,
                    sendMode = sendMode,
                    showEffectHint = false
                )
            }
        }
    }
}
