package com.bothbubbles.ui.chat.composer.panels

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bothbubbles.ui.chat.composer.ComposerPanel
import com.bothbubbles.ui.chat.composer.RecordingState
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens
import com.bothbubbles.ui.chat.composer.components.PanelDragHandle

/**
 * Host component that manages panel transitions and displays the active panel.
 *
 * This component handles:
 * - Smooth transitions between panels (slide, fade, scale)
 * - Panel-specific animations (opening vs. closing vs. switching)
 * - Coordinated visibility of multiple panel types
 * - Unified drag-to-dismiss gesture handling for all panels
 *
 * The drag handle is rendered inside each panel for visual consistency (MD3),
 * while the gesture handling is unified at this level.
 *
 * @param activePanel The currently active panel
 * @param onMediaSelected Callback when media is selected from gallery
 * @param onCameraClick Callback when camera is selected
 * @param onGifClick Callback when GIF panel is requested
 * @param onFileClick Callback when files is selected
 * @param onLocationClick Callback when location is selected
 * @param onAudioClick Callback when audio recording is requested
 * @param onContactClick Callback when contact sharing is selected
 * @param onEmojiSelected Callback when an emoji is selected
 * @param gifPickerState State for the GIF picker
 * @param gifSearchQuery Current GIF search query
 * @param onGifSearchQueryChange Callback when GIF search query changes
 * @param onGifSearch Callback when GIF search is submitted
 * @param onGifSelected Callback when a GIF is selected
 * @param onDismiss Callback to dismiss the panel
 * @param onDragDelta Callback when drag delta changes (for unified drag handling)
 * @param onDragStarted Callback when drag starts
 * @param onDragStopped Callback when drag stops with final offset
 * @param modifier Modifier for the host
 */
@Composable
fun ComposerPanelHost(
    activePanel: ComposerPanel,
    onMediaSelected: (List<Uri>) -> Unit,
    onCameraClick: () -> Unit,
    onGifClick: () -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onAudioClick: () -> Unit,
    onContactClick: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    gifPickerState: GifPickerState,
    gifSearchQuery: String,
    onGifSearchQueryChange: (String) -> Unit,
    onGifSearch: (String) -> Unit,
    onGifSelected: (GifItem) -> Unit,
    onDismiss: () -> Unit,
    onDragDelta: (Float) -> Unit = {},
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val draggableState = rememberDraggableState { delta ->
        onDragDelta(delta)
    }
    AnimatedContent(
        targetState = activePanel,
        transitionSpec = {
            when {
                // Opening panel from none
                targetState != ComposerPanel.None && initialState == ComposerPanel.None -> {
                    (slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(tween(ComposerMotionTokens.Duration.FAST)))
                        .togetherWith(fadeOut(tween(ComposerMotionTokens.Duration.INSTANT)))
                }
                // Closing panel to none
                targetState == ComposerPanel.None -> {
                    fadeIn(tween(ComposerMotionTokens.Duration.INSTANT))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(ComposerMotionTokens.Duration.NORMAL)
                            ) + fadeOut(tween(ComposerMotionTokens.Duration.FAST))
                        )
                }
                // Switching between panels
                else -> {
                    (fadeIn(tween(ComposerMotionTokens.Duration.NORMAL)) + scaleIn(
                        initialScale = ComposerMotionTokens.Scale.PanelSwitch,
                        animationSpec = tween(ComposerMotionTokens.Duration.NORMAL)
                    )).togetherWith(
                        fadeOut(tween(ComposerMotionTokens.Duration.FAST)) + scaleOut(
                            targetScale = ComposerMotionTokens.Scale.PanelSwitch
                        )
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
        label = "panel_host"
    ) { panel ->
        when (panel) {
            ComposerPanel.None -> {
                // Empty box when no panel is shown
                Box(modifier = Modifier.fillMaxWidth())
            }
            ComposerPanel.MediaPicker -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unified drag handle with gesture
                    PanelDragHandle(
                        modifier = Modifier.draggable(
                            state = draggableState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { onDragStarted() },
                            onDragStopped = { onDragStopped() }
                        )
                    )
                    MediaPickerPanel(
                        visible = true, // AnimatedContent handles visibility
                        onMediaSelected = onMediaSelected,
                        onCameraClick = onCameraClick,
                        onGifClick = onGifClick,
                        onFileClick = onFileClick,
                        onLocationClick = onLocationClick,
                        onAudioClick = onAudioClick,
                        onContactClick = onContactClick,
                        onDismiss = onDismiss
                    )
                }
            }
            ComposerPanel.EmojiKeyboard -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unified drag handle with gesture
                    PanelDragHandle(
                        modifier = Modifier.draggable(
                            state = draggableState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { onDragStarted() },
                            onDragStopped = { onDragStopped() }
                        )
                    )
                    EmojiKeyboardPanel(
                        visible = true,
                        onEmojiSelected = onEmojiSelected,
                        onDismiss = onDismiss
                    )
                }
            }
            ComposerPanel.GifPicker -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unified drag handle with gesture
                    PanelDragHandle(
                        modifier = Modifier.draggable(
                            state = draggableState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { onDragStarted() },
                            onDragStopped = { onDragStopped() }
                        )
                    )
                    GifPickerPanel(
                        visible = true,
                        state = gifPickerState,
                        searchQuery = gifSearchQuery,
                        onSearchQueryChange = onGifSearchQueryChange,
                        onSearch = onGifSearch,
                        onGifSelected = onGifSelected,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/**
 * Simplified panel host that doesn't require GIF state management.
 * Uses placeholder GIF panel state.
 */
@Composable
fun ComposerPanelHostSimple(
    activePanel: ComposerPanel,
    onMediaSelected: (List<Uri>) -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onAudioClick: () -> Unit,
    onContactClick: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onGifPanelRequest: () -> Unit = {},
    onDismiss: () -> Unit,
    onDragDelta: (Float) -> Unit = {},
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ComposerPanelHost(
        activePanel = activePanel,
        onMediaSelected = onMediaSelected,
        onCameraClick = onCameraClick,
        onGifClick = onGifPanelRequest,
        onFileClick = onFileClick,
        onLocationClick = onLocationClick,
        onAudioClick = onAudioClick,
        onContactClick = onContactClick,
        onEmojiSelected = onEmojiSelected,
        gifPickerState = GifPickerState.Idle,
        gifSearchQuery = "",
        onGifSearchQueryChange = {},
        onGifSearch = {},
        onGifSelected = {},
        onDismiss = onDismiss,
        onDragDelta = onDragDelta,
        onDragStarted = onDragStarted,
        onDragStopped = onDragStopped,
        modifier = modifier
    )
}
