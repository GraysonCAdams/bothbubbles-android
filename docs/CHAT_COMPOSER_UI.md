# Chat Composer UI - Google Messages-Inspired Redesign

This document outlines a migration plan to create a native Material Design 3 chat composer that emulates the Google Messages experience while retaining BothBubbles' unique iMessage/SMS switcher and guided tutorial UI.

---

## Table of Contents

1. [Design Vision](#design-vision)
2. [Google Messages Reference Analysis](#google-messages-reference-analysis)
3. [Component Architecture](#component-architecture)
4. [Animation System](#animation-system)
5. [iMessage/SMS Switcher Integration](#imessagesms-switcher-integration)
6. [Guided Tutorial System](#guided-tutorial-system)
7. [Migration Plan](#migration-plan)
8. [Implementation Phases](#implementation-phases)
9. [Integration Phase: Attachments System](#integration-phase-attachments-system)

---

## Design Vision

### Goals

- **Familiar UX**: Match Google Messages' intuitive, widely-adopted interaction patterns
- **Fluid Animations**: Smooth, spring-based animations that feel natural and responsive
- **Material Design 3**: Full MD3 compliance with dynamic color, typography, and motion tokens
- **Unique Identity**: Retain BothBubbles' distinctive iMessage/SMS switching capability
- **Accessibility**: Full a11y support with proper semantics and screen reader compatibility

### Design Principles

1. **Progressive Disclosure**: Show only what's needed, expand on demand
2. **Gestural Navigation**: Support swipe, long-press, and drag gestures
3. **Visual Continuity**: Seamless transitions between states
4. **Haptic Feedback**: Tactile confirmation for key interactions
5. **Muscle Memory**: Actions in expected locations (send = bottom-right)

---

## System Integration Strategy

To ensure a native feel and reduce maintenance burden, we will leverage Android system functionality and standard Material Design 3 components wherever possible:

### 1. Native Components vs. Custom Implementations

- **Text Input**: Use `androidx.compose.material3.TextField` with `TextFieldDefaults` for the input field. Avoid `BasicTextField` unless absolutely necessary for complex formatting.
- **Icons**: Use `androidx.compose.material.icons` (Filled/Outlined/AutoMirrored) instead of custom SVGs.
- **Chips**: Use `androidx.compose.material3.SuggestionChip` and `FlowRow` for Smart Replies.
- **Bottom Sheets**: Use `androidx.compose.material3.ModalBottomSheet` for the "plus" menu.

### 2. System Integration

- **Keyboard Handling**: Use `WindowInsets.ime` and `Modifier.imePadding()` for seamless keyboard transitions instead of manual height calculations.
- **Media Picker**: Integrate the **Android Photo Picker** (`ActivityResultContracts.PickVisualMedia`) as the primary media selection tool, falling back to a custom sheet only for specific needs (like Giphy).
- **Haptics**: Use `LocalHapticFeedback.current` with `HapticFeedbackType.TextHandleMove` and `LongPress` for rich tactile feedback.
- **Accessibility**: Ensure all custom touch targets have `Modifier.semantics` with proper `contentDescription`, `stateDescription`, and `onClick` labels.

---

## Google Messages Reference Analysis

### Composer Layout (Google Messages 2024)

```
┌────────────────────────────────────────────────────────────────┐
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ [Reply Preview - if replying to message]                 │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ [Attachment Thumbnails Row - if attachments selected]    │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │                    Smart Reply Chips                      │   │
│ │         [Thanks!]  [Sure]  [On my way]                   │   │
│ └──────────────────────────────────────────────────────────┘   │
│ ┌──────────────────────────────────────────────────────────────┐
│ │ ╭────────────────────────────────────────────────────────╮ │
│ │ │ [+] │ Message text input field...          [📷] [😊] │ │
│ │ ╰────────────────────────────────────────────────────────╯ │
│ │                                                      [▶] │◄── Send Button
│ └──────────────────────────────────────────────────────────────┘
└────────────────────────────────────────────────────────────────┘
```

### Key Characteristics

| Feature               | Google Messages Behavior                              |
| --------------------- | ----------------------------------------------------- |
| **Input Field Shape** | Stadium/pill shape with 24dp corner radius            |
| **Field Background**  | Surface variant color, subtle contrast                |
| **Add Button**        | Left-aligned, opens bottom sheet with media options   |
| **Camera Button**     | Inline quick-capture, transforms to emoji when typing |
| **Emoji Button**      | Shows emoji picker, replaces keyboard                 |
| **Send Button**       | Separate circular FAB, outside input field            |
| **Voice Button**      | Replaces send when empty, hold-to-record              |
| **Attachments**       | Horizontal scroll above input, dismissible chips      |
| **Smart Replies**     | Horizontal chips above input, fade in after receive   |

### Animation Patterns

1. **Send Button Morph**: Microphone → Send icon crossfade (150ms)
2. **Add Button Rotation**: 45° rotation when drawer opens (200ms, FastOutSlowIn)
3. **Smart Reply Entrance**: Staggered fade+slide from right (50ms delay per chip)
4. **Attachment Preview Expand**: Height animation with content fade (250ms)
5. **Input Field Grow**: Smooth height transition for multiline (spring-based)
6. **Keyboard Sync**: Input area slides with keyboard (IME animation sync)

---

## Component Architecture

### New Component Structure

```
ui/chat/composer/
├── ChatComposer.kt                 # Main orchestrating component
├── ComposerState.kt                # Unified state management
├── components/
│   ├── ComposerTextField.kt        # Wrapper around MD3 TextField
│   ├── ComposerActionButtons.kt    # Left-side action buttons
│   ├── ComposerMediaButtons.kt     # Right-side media buttons
│   ├── ComposerSendButton.kt       # Unified send/voice button
│   ├── AttachmentThumbnailRow.kt   # Horizontal attachment previews (LazyRow)
│   ├── ReplyPreviewBar.kt          # Quote reply preview
│   └── SmartReplyRow.kt            # ML suggestion chips (FlowRow)
├── panels/
│   ├── MediaPickerPanel.kt         # Wrapper for Android Photo Picker
│   ├── EmojiKeyboardPanel.kt       # Emoji picker (replaces keyboard)
│   ├── GifPickerPanel.kt           # GIF search and selection
│   └── VoiceMemoPanel.kt           # Recording UI
├── gestures/
│   ├── SendModeGestureHandler.kt   # iMessage/SMS swipe logic
│   └── VoiceRecordGestureHandler.kt # Hold-to-record
├── tutorial/
│   ├── ComposerTutorial.kt         # Tutorial orchestrator
│   ├── SendModeTutorialStep.kt     # iMessage/SMS teaching
│   └── TutorialSpotlight.kt        # Highlight overlay
└── animations/
    ├── ComposerMotionTokens.kt     # Animation constants
    └── ComposerTransitions.kt      # Reusable transition specs
```

### State Architecture

```kotlin
/**
 * Unified composer state with clear separation of concerns.
 */
@Immutable
data class ComposerState(
    // Text Input
    val text: String = "",
    val cursorPosition: Int = 0,
    val isTextFieldFocused: Boolean = false,

    // Attachments
    val attachments: List<AttachmentItem> = emptyList(),
    val attachmentWarning: AttachmentWarning? = null,

    // Reply
    val replyToMessage: MessagePreview? = null,

    // Smart Replies
    val smartReplies: List<String> = emptyList(),
    val showSmartReplies: Boolean = false,

    // Send Mode
    val sendMode: ChatSendMode = ChatSendMode.IMESSAGE,
    val canToggleSendMode: Boolean = false,
    val isSendModeAnimating: Boolean = false,

    // Panels
    val activePanel: ComposerPanel = ComposerPanel.None,

    // Input Mode
    val inputMode: InputMode = InputMode.TEXT,

    // Voice Recording
    val recordingState: RecordingState? = null,

    // Tutorial
    val tutorialState: TutorialState = TutorialState.Hidden,

    // Sending
    val isSending: Boolean = false,
    val sendProgress: Float? = null
) {
    val canSend: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty()

    val showVoiceButton: Boolean
        get() = text.isBlank() && attachments.isEmpty() && inputMode == InputMode.TEXT
}

enum class ComposerPanel {
    None,
    MediaPicker,
    EmojiKeyboard,
    GifPicker
}

enum class InputMode {
    TEXT,
    VOICE_RECORDING,
    VOICE_PREVIEW
}
```

---

## Animation System

### Motion Tokens (MD3 Aligned)

```kotlin
object ComposerMotionTokens {
    // Durations
    object Duration {
        const val INSTANT = 50           // Micro-interactions
        const val FAST = 100             // Button state changes
        const val NORMAL = 200           // Standard transitions
        const val EMPHASIZED = 300       // Key transitions
        const val COMPLEX = 400          // Multi-element animations
        const val STAGGER_DELAY = 50     // Per-item stagger
    }

    // Spring Configurations
    object Spring {
        val Snappy = SpringSpec<Float>(
            dampingRatio = 0.7f,
            stiffness = 800f
        )
        val Responsive = SpringSpec<Float>(
            dampingRatio = 0.8f,
            stiffness = 400f
        )
        val Gentle = SpringSpec<Float>(
            dampingRatio = 0.9f,
            stiffness = 200f
        )
        val Bouncy = SpringSpec<Float>(
            dampingRatio = 0.6f,
            stiffness = 500f
        )
    }

    // Easing Curves (MD3)
    object Easing {
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    }
}
```

### Key Animations

#### 1. Send Button Morph

```kotlin
@Composable
fun AnimatedSendButton(
    canSend: Boolean,
    sendMode: ChatSendMode,
    isSending: Boolean,
    onSend: () -> Unit,
    onVoiceStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconRotation by animateFloatAsState(
        targetValue = if (canSend) 0f else 360f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        )
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSending -> MaterialTheme.colorScheme.surfaceVariant
            sendMode == ChatSendMode.SMS -> SmsGreen
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing)
    )

    // Crossfade between icons with scale effect
    Crossfade(
        targetState = canSend,
        animationSpec = tween(150),
        modifier = modifier
    ) { showSend ->
        if (showSend) {
            // Send icon with rotation entrance
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier.graphicsLayer {
                    rotationZ = iconRotation
                    scaleX = 1f - (iconRotation / 360f) * 0.2f
                    scaleY = 1f - (iconRotation / 360f) * 0.2f
                }
            )
        } else {
            // Microphone icon
            Icon(Icons.Outlined.Mic, contentDescription = "Voice memo")
        }
    }
}
```

#### 2. Smart Reply Staggered Entrance

```kotlin
@Composable
fun SmartReplyRow(
    replies: List<String>,
    visible: Boolean,
    onReplyClick: (String) -> Unit
) {
    AnimatedVisibility(
        visible = visible && replies.isNotEmpty(),
        enter = fadeIn(tween(200)) + expandVertically(
            animationSpec = spring(dampingRatio = 0.8f)
        ),
        exit = fadeOut(tween(150)) + shrinkVertically()
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            itemsIndexed(replies) { index, reply ->
                StaggeredReplyChip(
                    text = reply,
                    delayMs = index * 50,
                    onClick = { onReplyClick(reply) }
                )
            }
        }
    }
}

@Composable
private fun StaggeredReplyChip(
    text: String,
    delayMs: Int,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 500f
        )
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200)
    )

    SuggestionChip(
        onClick = onClick,
        label = { Text(text) },
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
    )
}
```

#### 3. Attachment Row Expand/Collapse

```kotlin
@Composable
fun AttachmentThumbnailRow(
    attachments: List<AttachmentItem>,
    onRemove: (AttachmentItem) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = attachments.isNotEmpty(),
        transitionSpec = {
            (fadeIn(tween(200)) + expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = spring(dampingRatio = 0.85f)
            )).togetherWith(
                fadeOut(tween(150)) + shrinkVertically(
                    shrinkTowards = Alignment.Bottom
                )
            )
        },
        modifier = modifier
    ) { hasAttachments ->
        if (hasAttachments) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(
                    items = attachments,
                    key = { it.id }
                ) { attachment ->
                    AttachmentThumbnail(
                        attachment = attachment,
                        onRemove = { onRemove(attachment) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(200),
                            fadeOutSpec = tween(150),
                            placementSpec = spring(dampingRatio = 0.8f)
                        )
                    )
                }
            }
        }
    }
}
```

#### 4. Panel Slide Transitions

```kotlin
@Composable
fun ComposerPanelHost(
    activePanel: ComposerPanel,
    onDismiss: () -> Unit,
    mediaContent: @Composable () -> Unit,
    emojiContent: @Composable () -> Unit,
    gifContent: @Composable () -> Unit
) {
    val panelHeight = 280.dp

    AnimatedContent(
        targetState = activePanel,
        transitionSpec = {
            when {
                // Opening panel
                targetState != ComposerPanel.None && initialState == ComposerPanel.None -> {
                    (slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = spring(
                            dampingRatio = 0.85f,
                            stiffness = 400f
                        )
                    ) + fadeIn(tween(100)))
                        .togetherWith(fadeOut(tween(50)))
                }
                // Closing panel
                targetState == ComposerPanel.None -> {
                    fadeIn(tween(50))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(250, easing = FastOutSlowInEasing)
                            ) + fadeOut(tween(100))
                        )
                }
                // Switching panels
                else -> {
                    (fadeIn(tween(150)) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(150)
                    )).togetherWith(
                        fadeOut(tween(100)) + scaleOut(targetScale = 0.95f)
                    )
                }
            }
        }
    ) { panel ->
        when (panel) {
            ComposerPanel.None -> Spacer(Modifier.height(0.dp))
            ComposerPanel.MediaPicker -> mediaContent()
            ComposerPanel.EmojiKeyboard -> emojiContent()
            ComposerPanel.GifPicker -> gifContent()
        }
    }
}
```

---

## iMessage/SMS Switcher Integration

### Unified Send Button with Mode Toggle

The key differentiator for BothBubbles is the iMessage/SMS switcher. This must be:

- **Discoverable**: First-time users learn the gesture naturally
- **Non-intrusive**: Experienced users can ignore it
- **Reliable**: Mode changes are visually confirmed

### Design Concept

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SEND BUTTON STATES                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   IDLE (iMessage)          IDLE (SMS)           SWIPING             │
│   ┌─────────┐              ┌─────────┐          ┌─────────┐         │
│   │  ▶      │              │  ▶      │          │ ▲ ▶ ▼   │         │
│   │ (blue)  │              │ (green) │          │(reveal) │         │
│   └─────────┘              └─────────┘          └─────────┘         │
│                                                                     │
│   REVEAL ANIMATION (First load for eligible chats):                 │
│   ┌─────────┐  ──▶  ┌─────────┐  ──▶  ┌─────────┐                  │
│   │ ◐ ◐    │       │ ~~~wave │       │ (solid) │                   │
│   │blue grn │       │         │       │         │                   │
│   └─────────┘       └─────────┘       └─────────┘                   │
│   Split view        Wave effect       Settles to current mode      │
│                                                                     │
│   TUTORIAL OVERLAY:                                                 │
│   ┌──────────────────────────────────────────────────┐             │
│   │           ↑                                       │             │
│   │    "Swipe up for SMS"                            │             │
│   │    ○ ● (step indicator)                          │             │
│   │                                                   │             │
│   │         [spotlight on send button]               │             │
│   └──────────────────────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
```

### Swipe Gesture Mechanics

```kotlin
object SendModeGestureConfig {
    // Gesture detection
    const val SWIPE_RANGE_DP = 60f              // Total vertical swipe range
    const val SNAP_THRESHOLD_PERCENT = 0.75f    // 75% triggers mode switch
    const val DETECTION_DISTANCE_DP = 12f       // Min distance to start gesture
    const val DIRECTION_RATIO = 1.5f            // Vertical must exceed horizontal by 1.5x

    // Animation timing
    const val REVEAL_DURATION_MS = 1800         // Initial reveal animation
    const val REVEAL_HOLD_MS = 800              // Hold split before filling
    const val WAVE_LOOP_MS = 2000               // Wave animation loop
    const val SETTLE_DURATION_MS = 1000         // Fill to solid color

    // Haptics
    val THRESHOLD_HAPTIC = HapticFeedbackType.LongPress
    val MODE_CHANGE_HAPTIC = HapticFeedbackType.TextHandleMove
}
```

### Visual Feedback During Swipe

```kotlin
@Composable
fun SendModeToggleButton(
    currentMode: ChatSendMode,
    canToggle: Boolean,
    onModeToggle: (ChatSendMode) -> Boolean,
    isSending: Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var dragProgress by remember { mutableStateOf(0f) }
    var targetMode by remember { mutableStateOf(currentMode) }

    val iMessageColor = BothBubblesTheme.bubbleColors.iMessageSent
    val smsColor = SmsGreen

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (canToggle) {
                    Modifier.pointerInput(currentMode) {
                        detectVerticalDragGestures(
                            onDragStart = { /* visual feedback start */ },
                            onDragEnd = {
                                // Snap to mode if threshold crossed
                                if (abs(dragProgress) >= 0.75f) {
                                    val newMode = if (dragProgress > 0)
                                        ChatSendMode.SMS else ChatSendMode.IMESSAGE
                                    if (onModeToggle(newMode)) {
                                        haptic.performHapticFeedback(MODE_CHANGE_HAPTIC)
                                    }
                                }
                                // Animate back to center
                                dragProgress = 0f
                            },
                            onDrag = { change, dragAmount ->
                                val newProgress = (dragProgress + dragAmount / swipeRangePx)
                                    .coerceIn(-1f, 1f)

                                // Haptic at threshold
                                if (abs(newProgress) >= 0.75f && abs(dragProgress) < 0.75f) {
                                    haptic.performHapticFeedback(THRESHOLD_HAPTIC)
                                }

                                dragProgress = newProgress
                            }
                        )
                    }
                } else Modifier
            )
            .drawBehind {
                // Draw dual-color reveal based on dragProgress
                drawSendModeBackground(
                    progress = dragProgress,
                    currentMode = currentMode,
                    iMessageColor = iMessageColor,
                    smsColor = smsColor
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Send icon with mode label
        SendIcon(mode = currentMode, isSending = isSending)

        // Mode label during drag
        if (abs(dragProgress) > 0.2f) {
            ModeLabel(
                mode = targetMode,
                alpha = abs(dragProgress)
            )
        }
    }
}
```

---

## Guided Tutorial System

### Tutorial Flow

```
┌────────────────────────────────────────────────────────────────────┐
│                      TUTORIAL SEQUENCE                              │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  TRIGGER: First message attempt in a chat that supports both modes │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  STEP 1: Introduction                                        │  │
│  │                                                               │  │
│  │  [Dimmed overlay with spotlight on send button]              │  │
│  │                                                               │  │
│  │  "You can send as iMessage or SMS"                           │  │
│  │  "Swipe up on the send button to switch"                     │  │
│  │                                                               │  │
│  │  [Visual: Arrow pointing up from button]                     │  │
│  │  [Progress: ○ ● ●]                                           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                           │                                        │
│                           ▼ (user swipes up)                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  STEP 2: Confirm Switch                                      │  │
│  │                                                               │  │
│  │  [Button now shows SMS green]                                │  │
│  │                                                               │  │
│  │  "Great! Now you're set to send SMS"                         │  │
│  │  "Swipe down to go back to iMessage"                         │  │
│  │                                                               │  │
│  │  [Visual: Arrow pointing down]                               │  │
│  │  [Progress: ● ○ ●]                                           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                           │                                        │
│                           ▼ (user swipes down)                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  STEP 3: Complete                                            │  │
│  │                                                               │  │
│  │  [Celebration animation - confetti or checkmark]             │  │
│  │                                                               │  │
│  │  "You're all set!"                                           │  │
│  │  "The button color shows your current mode"                  │  │
│  │                                                               │  │
│  │  [Auto-dismiss after 1.5s]                                   │  │
│  │  [Progress: ● ● ○]                                           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  PERSISTENCE: tutorialCompleted flag saved in DataStore            │
│  SKIP: User can tap anywhere on dimmed area to skip               │
│  RE-TRIGGER: Settings option to replay tutorial                    │
└────────────────────────────────────────────────────────────────────┘
```

### Tutorial Overlay Component

```kotlin
@Composable
fun SendModeTutorial(
    state: TutorialState,
    sendButtonBounds: Rect,
    onStepComplete: (TutorialStep) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == TutorialState.Hidden) return

    val step = state.currentStep
    val spotlightRadius by animateFloatAsState(
        targetValue = if (step.isActive) 40.dp.value else 0f,
        animationSpec = spring(dampingRatio = 0.7f)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Semi-transparent overlay with spotlight cutout
                drawRect(Color.Black.copy(alpha = 0.7f))
                drawCircle(
                    color = Color.Transparent,
                    radius = spotlightRadius,
                    center = sendButtonBounds.center,
                    blendMode = BlendMode.Clear
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        // Instruction card positioned above or below button
        TutorialCard(
            step = step,
            buttonBounds = sendButtonBounds,
            onComplete = { onStepComplete(step) }
        )

        // Animated gesture hint (arrow)
        GestureHintArrow(
            direction = step.gestureDirection,
            anchor = sendButtonBounds.center
        )

        // Step progress indicator
        StepProgressDots(
            totalSteps = 3,
            currentStep = step.ordinal,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
private fun GestureHintArrow(
    direction: GestureDirection,
    anchor: Offset
) {
    val infiniteTransition = rememberInfiniteTransition()
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (direction == GestureDirection.UP) -20f else 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    Icon(
        imageVector = if (direction == GestureDirection.UP)
            Icons.Filled.KeyboardArrowUp
        else
            Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .size(48.dp)
    )
}
```

---

## Migration Plan

### Phase Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MIGRATION PHASES                              │
├──────────┬──────────────────────────────────────────────────────────┤
│ Phase 1  │ Foundation & State Architecture (COMPLETED)              │
│ (Week 1) │ - Create new composer package structure [x]              │
│          │ - Implement ComposerState and ViewModel [x]              │
│          │ - Set up motion tokens and animation utilities [x]       │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 2  │ Core Components (COMPLETED)                              │
│ (Week 2) │ - ComposerTextField with MD3 styling [x]                 │
│          │ - Action buttons (add, camera, emoji) [x]                │
│          │ - Basic layout matching Google Messages [x]              │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 3  │ Send Button & Mode Toggle (COMPLETED)                    │
│ (Week 3) │ - Unified send/voice button [x]                          │
│          │ - iMessage/SMS swipe gesture [x]                         │
│          │ - Reveal animation (Pepsi effect) [x]                    │
│          │ - Visual feedback during drag [x]                        │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 4  │ Tutorial System (COMPLETED)                              │
│ (Week 4) │ - Spotlight overlay component [x]                        │
│          │ - Step-by-step tutorial flow [x]                         │
│          │ - Persistence and skip functionality [x]                 │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 5  │ Panels & Extended Features (COMPLETED)                   │
│ (Week 5) │ - Media picker panel (Google Messages style) [x]         │
│          │ - Emoji keyboard integration [x]                         │
│          │ - Voice recording (hold-to-record) [x]                   │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 6  │ Smart Features (COMPLETED)                               │
│ (Week 6) │ - Smart reply chips [x]                                  │
│          │ - Attachment previews [x]                                │
│          │ - Reply preview bar [x]                                  │
├──────────┼──────────────────────────────────────────────────────────┤
│ Phase 7  │ Polish & Integration                                     │
│ (Week 7) │ - Animation refinement                                   │
│          │ - Accessibility audit                                    │
│          │ - Integration with ChatScreen                            │
│          │ - Remove old ChatInputArea                               │
└──────────┴──────────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Foundation & State Architecture (COMPLETED)

**Files to Create:**

```
ui/chat/composer/
├── ComposerState.kt
├── ComposerViewModel.kt  (or integrate into ChatViewModel)
└── animations/
    └── ComposerMotionTokens.kt
```

**Tasks:**

- [x] **Create ComposerState data class** with all UI state
- [x] **Define motion tokens** aligned with MD3 specifications
- [x] **Create ComposerPanel enum** for panel switching
- [x] **Set up InputMode enum** for text/voice modes
- [x] **Add TutorialState** sealed class for tutorial flow

**ComposerMotionTokens.kt:**

```kotlin
package com.bothbubbles.ui.chat.composer.animations

import androidx.compose.animation.core.*

object ComposerMotionTokens {
    object Duration {
        const val INSTANT = 50
        const val FAST = 100
        const val NORMAL = 200
        const val EMPHASIZED = 300
        const val COMPLEX = 400
        const val STAGGER = 50
    }

    object Spring {
        val Snappy = spring<Float>(dampingRatio = 0.7f, stiffness = 800f)
        val Responsive = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)
        val Gentle = spring<Float>(dampingRatio = 0.9f, stiffness = 200f)
        val Bouncy = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
    }

    object Easing {
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }
}
```

---

### Phase 2: Core Components (COMPLETED)

**Files Created:**

```
ui/chat/composer/
├── ChatComposer.kt             ✓
└── components/
    ├── ComposerTextField.kt    ✓
    ├── ComposerActionButtons.kt ✓
    └── ComposerMediaButtons.kt  ✓
```

**Tasks:**

1. [x] **ComposerTextField**: MD3-styled text input

   - Stadium/pill shape with rounded corners
   - Surface variant background
   - Grows to max 4 lines
   - Placeholder text changes based on send mode

2. [x] **ComposerActionButtons** (left side):

   - Add button with rotation animation
   - Expands to show quick actions

3. [x] **ComposerMediaButtons** (right side):

   - Camera icon (transforms when typing)
   - Emoji icon
   - Image/gallery icon

4. [x] **ChatComposer**: Main layout orchestrator
   - Horizontal Row layout
   - Proper spacing and alignment
   - Voice recording and preview modes
   - All input modes with animated transitions

**Layout Structure:**

```kotlin
@Composable
fun ChatComposer(
    state: ComposerState,
    onEvent: (ComposerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Optional: Reply preview
        ReplyPreviewBar(state.replyToMessage, onDismiss = { onEvent(DismissReply) })

        // Optional: Attachments
        AttachmentThumbnailRow(state.attachments, onRemove = { onEvent(RemoveAttachment(it)) })

        // Optional: Smart replies
        SmartReplyRow(state.smartReplies, state.showSmartReplies, onClick = { onEvent(SelectSmartReply(it)) })

        // Main input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Left: Add button
            ComposerActionButtons(
                isExpanded = state.activePanel == ComposerPanel.MediaPicker,
                onAddClick = { onEvent(ToggleMediaPicker) }
            )

            // Center: Text field
            ComposerTextField(
                text = state.text,
                onTextChange = { onEvent(TextChanged(it)) },
                placeholder = if (state.sendMode == ChatSendMode.SMS) "SMS" else "iMessage",
                modifier = Modifier.weight(1f)
            )

            // Right: Media buttons
            ComposerMediaButtons(
                showCamera = state.text.isBlank(),
                onCameraClick = { onEvent(OpenCamera) },
                onEmojiClick = { onEvent(ToggleEmojiPicker) }
            )

            Spacer(Modifier.width(8.dp))

            // Far right: Send button
            ComposerSendButton(
                canSend = state.canSend,
                sendMode = state.sendMode,
                canToggle = state.canToggleSendMode,
                isSending = state.isSending,
                onSend = { onEvent(Send) },
                onVoiceStart = { onEvent(StartVoiceRecording) },
                onModeToggle = { onEvent(ToggleSendMode(it)) }
            )
        }

        // Expandable panels
        ComposerPanelHost(state.activePanel, ...)
    }
}
```

---

### Phase 3: Send Button & Mode Toggle (COMPLETED)

**Files Created:**

```
ui/chat/composer/
├── components/
│   └── ComposerSendButton.kt      ✓
└── gestures/
    └── SendModeGestureHandler.kt  ✓
```

**Tasks:**

- [x] **Port SendModeToggleButton logic** to new component
- [x] **Implement vertical drag gesture** detection (extracted to SendModeGestureHandler)
- [x] **Create reveal animation** (Pepsi effect):
   - Dual-color split on chat load
   - Wave animation during hold
   - Settle to current mode color
- [x] **Add haptic feedback** at threshold
- [x] **Visual drag progress** indicator

**Key Implementation Details:**

```kotlin
// Gesture detection constants
object SendModeGestureConfig {
    const val SWIPE_RANGE_DP = 60f
    const val SNAP_THRESHOLD = 0.75f
    const val DETECTION_DISTANCE_DP = 12f
    const val DIRECTION_RATIO = 1.5f
}

// Reveal animation phases
enum class RevealPhase {
    SPLIT,      // Show both colors
    WAVE,       // Animate wave divider
    SETTLING,   // Fill to single color
    COMPLETE    // Normal state
}
```

---

### Phase 4: Tutorial System (COMPLETED)

**Files Created:**

```
ui/chat/composer/tutorial/
├── ComposerTutorial.kt        ✓
├── SendModeTutorialStep.kt    ✓
├── TutorialSpotlight.kt       ✓
└── TutorialState.kt           ✓
```

**Tasks:**

- [x] **Spotlight overlay** with cutout for send button (TutorialSpotlight.kt)
- [x] **Instruction cards** with step content (TutorialCard in SendModeTutorialStep.kt)
- [x] **Animated gesture hints** (GestureHintArrow with bidirectional support)
- [x] **Step progress dots** (StepProgressDots component)
- [x] **Completion celebration** animation (CompletionIcon with checkmark)
- [x] **Persistence** via DataStore (integrated with existing hasCompletedSendModeTutorial)

**TutorialState:**

```kotlin
sealed class TutorialState {
    object Hidden : TutorialState()
    data class Active(val step: TutorialStep) : TutorialState()
    object Completing : TutorialState()
}

enum class TutorialStep(
    val title: String,
    val description: String,
    val gestureDirection: GestureDirection
) {
    INTRO(
        title = "Switch between iMessage and SMS",
        description = "Swipe up on the send button to switch to SMS",
        gestureDirection = GestureDirection.UP
    ),
    CONFIRM(
        title = "Now sending as SMS",
        description = "Swipe down to go back to iMessage",
        gestureDirection = GestureDirection.DOWN
    ),
    COMPLETE(
        title = "You're all set!",
        description = "The button color shows your current mode",
        gestureDirection = GestureDirection.NONE
    )
}
```

---

### Phase 5: Panels & Extended Features (COMPLETED)

**Files Created:**

```
ui/chat/composer/panels/
├── MediaPickerPanel.kt       ✓
├── EmojiKeyboardPanel.kt     ✓
├── GifPickerPanel.kt         ✓
├── VoiceMemoPanel.kt         ✓
└── ComposerPanelHost.kt      ✓
```

**Tasks:**

- [x] **MediaPickerPanel**: Grid of options (Gallery, Camera, GIFs, Files, Location, Audio, Contact)
   - Uses Android Photo Picker (PickMultipleVisualMedia)
   - Color-coded icons for each option
   - Spring-based slide-in animations
- [x] **EmojiKeyboardPanel**: Emoji picker that replaces keyboard
   - Category tabs with icons
   - 8-column emoji grid
   - Smooth slide-up transition
- [x] **GifPickerPanel**: Search and select GIFs (Giphy/Tenor integration ready)
   - Search bar with auto-focus
   - Grid of GIF results
   - Loading, error, and empty states
   - API integration hooks for Giphy/Tenor
- [x] **VoiceMemoPanel**: Voice recording with waveform visualization
   - Real-time waveform from amplitude data
   - Pulsing recording indicator
   - Noise cancellation toggle
   - Preview mode with playback controls
- [x] **ComposerPanelHost**: Panel transition orchestrator
   - AnimatedContent with custom transitions
   - Spring-based panel opening
   - Scale/fade for panel switching
   - Slide-out for panel closing

**Panel Animations:**

- Slide up from bottom with spring overshoot
- Scale/fade transitions when switching panels
- Slide down when closing

---

### Phase 6: Smart Features (COMPLETED)

**Files Created:**

```
ui/chat/composer/components/
├── SmartReplyRow.kt           ✓
├── AttachmentThumbnailRow.kt  ✓
└── ReplyPreviewBar.kt         ✓
```

**Tasks:**

- [x] **SmartReplyRow**: Staggered entrance animation with bouncy springs
   - Max 3 chips displayed from smart replies list
   - Fade + scale entrance with per-chip delay (50ms stagger)
   - Material 3 SuggestionChip styling
   - Right-aligned layout matching Google Messages
- [x] **AttachmentThumbnailRow**: Horizontal scroll with expand/collapse
   - LazyRow with animated item placement
   - Remove button overlay on each thumbnail
   - Edit button for image attachments
   - Upload progress indicator
   - Error state with retry overlay
   - Quality indicator in header row
   - Clear all button for multiple attachments
- [x] **ReplyPreviewBar**: Quote preview with slide animation
   - Colored accent bar on left (primary color)
   - "Replying to {name}" label with sender name
   - Message text preview (single line, truncated)
   - Attachment indicator icon when applicable
   - Dismiss button to cancel reply
   - Expand/shrink from top animation

---

### Phase 7: Polish & Integration

**Tasks:**

1. **Animation Refinement**:

   - Test all transitions for smoothness
   - Adjust spring parameters
   - Ensure 60fps on mid-range devices

2. **Accessibility**:

   - Content descriptions for all buttons
   - Announce mode changes
   - Support TalkBack gestures
   - Keyboard navigation

3. **Integration with ChatScreen**:

   - Replace `ChatInputArea` usage
   - Connect to `ChatViewModel`
   - Wire up all events

4. **Cleanup**:
   - Remove old `ChatInputArea.kt` and related components
   - Update imports
   - Remove deprecated code

---

## File Mapping: Old → New

| Old File                     | New File(s)                                                                                      | Notes                         |
| ---------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------- |
| `ChatInputArea.kt`           | `ChatComposer.kt`, `ComposerTextField.kt`, `ComposerActionButtons.kt`, `ComposerMediaButtons.kt` | Split into focused components |
| `SendModeToggleButton.kt`    | `ComposerSendButton.kt`, `SendModeGestureHandler.kt`                                             | Cleaner separation            |
| `SendModeTutorialOverlay.kt` | `tutorial/*` (ComposerTutorial, TutorialSpotlight, SendModeTutorialStep, TutorialState)          | Full tutorial package ✓       |
| `SmartReplyChips.kt`         | `SmartReplyRow.kt`                                                                               | Renamed, enhanced animations ✓|
| `AttachmentPickerPanel.kt`   | `panels/MediaPickerPanel.kt`                                                                     | Google Messages style grid ✓  |
| `EmojiPickerPanel.kt`        | `panels/EmojiKeyboardPanel.kt`                                                                   | Keyboard replacement ✓        |
| (new)                        | `panels/GifPickerPanel.kt`                                                                       | GIF search and selection ✓    |
| (new)                        | `panels/VoiceMemoPanel.kt`                                                                       | Voice recording panel ✓       |
| (new)                        | `panels/ComposerPanelHost.kt`                                                                    | Panel transition host ✓       |
| `ReplyPreview.kt`            | `ReplyPreviewBar.kt`                                                                             | Enhanced with animations ✓    |
| (new)                        | `AttachmentThumbnailRow.kt`                                                                      | Attachment strip component ✓  |
| `SendModeToggleState.kt`     | `ComposerState.kt`                                                                               | Unified state                 |

---

## Integration Phase: Attachments System

This phase bridges the Chat Composer UI with the Attachments feature system (see `ATTACHMENTS_PLAN.md`).

### Integration Points

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    COMPOSER ↔ ATTACHMENTS INTEGRATION                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  CHAT COMPOSER                           ATTACHMENTS SYSTEM             │
│  ──────────────                          ──────────────────             │
│                                                                         │
│  ┌─────────────────────┐                 ┌─────────────────────┐        │
│  │ AttachmentThumbnail │ ───────────────▶│ AttachmentErrorState│        │
│  │ Row.kt              │   uses error    │ (util/error/)       │        │
│  │                     │◀─────────────── │                     │        │
│  │                     │   displays      │ AttachmentError     │        │
│  │                     │   overlay       │ Overlay.kt          │        │
│  └─────────────────────┘                 └─────────────────────┘        │
│           │                                        ▲                    │
│           │ provides                               │                    │
│           ▼                                        │                    │
│  ┌─────────────────────┐                 ┌─────────────────────┐        │
│  │ ComposerState       │                 │ PendingAttachment   │        │
│  │ .attachments        │ ────────────────│ Entity              │        │
│  │ .attachmentWarning  │   syncs with    │ .errorType          │        │
│  └─────────────────────┘                 │ .quality            │        │
│           │                              └─────────────────────┘        │
│           │ tap thumbnail                                               │
│           ▼                                                             │
│  ┌─────────────────────┐                 ┌─────────────────────┐        │
│  │ Edit action         │ ───────────────▶│ AttachmentEdit      │        │
│  │ (thumbnail overlay) │   launches      │ Screen.kt           │        │
│  └─────────────────────┘                 │ (crop/rotate/draw)  │        │
│                                          └─────────────────────┘        │
│  ┌─────────────────────┐                 ┌─────────────────────┐        │
│  │ MediaPickerPanel.kt │ ───────────────▶│ PickVisualMedia     │        │
│  │                     │   uses native   │ (Photo Picker API)  │        │
│  │                     │◀─────────────── │                     │        │
│  │                     │   returns URIs  │                     │        │
│  └─────────────────────┘                 └─────────────────────┘        │
│           │                                                             │
│           │ opens sheet                                                 │
│           ▼                                                             │
│  ┌─────────────────────┐                 ┌─────────────────────┐        │
│  │ Quality icon        │ ───────────────▶│ QualitySelection    │        │
│  │ (in action bar)     │   opens         │ Sheet.kt            │        │
│  └─────────────────────┘                 └─────────────────────┘        │
│                                                    │                    │
│                                                    ▼                    │
│                                          ┌─────────────────────┐        │
│                                          │ ImageCompressor.kt  │        │
│                                          │ (applies on send)   │        │
│                                          └─────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────┘
```

### When to Integrate

**Execute this integration AFTER:**
- Composer Phase 5 (Panels) is complete
- Composer Phase 6 (AttachmentThumbnailRow) is complete
- Attachments Phase 1 (Error States) is complete

**Execute this integration BEFORE:**
- Composer Phase 7 (Polish & Integration)

### Integration Tasks

#### Task 1: Wire Error States to Thumbnails

**Composer Side:**
```kotlin
// In AttachmentThumbnailRow.kt
@Composable
fun AttachmentThumbnail(
    attachment: AttachmentItem,
    onRemove: () -> Unit,
    onRetry: () -> Unit,  // ADD: retry callback
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        // ... thumbnail content ...

        // ADD: Error overlay from attachments system
        if (attachment.errorState != null) {
            AttachmentErrorOverlay(
                error = attachment.errorState,
                onRetry = onRetry,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
```

**Attachments Side:**
- Ensure `AttachmentErrorOverlay` accepts size constraints from parent
- Export error state mapping function for `PendingAttachmentEntity` → `AttachmentItem`

#### Task 2: Connect MediaPickerPanel to Photo Picker

**Composer Side:**
```kotlin
// In MediaPickerPanel.kt
@Composable
fun MediaPickerPanel(
    onMediaSelected: (List<Uri>) -> Unit,
    onDismiss: () -> Unit
) {
    // Use Photo Picker launcher from attachments system
    val pickMedia = rememberLauncherForActivityResult(
        PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onMediaSelected(uris)
        }
    }

    // Panel content with "Gallery" button that launches picker
    MediaPickerGrid(
        onGalleryClick = {
            pickMedia.launch(PickVisualMediaRequest(ImageAndVideo))
        },
        // ... other options
    )
}
```

#### Task 3: Add Quality Indicator to Composer

**Composer Side:**
```kotlin
// In ComposerActionButtons.kt - add quality icon when attachments present
@Composable
fun ComposerActionButtons(
    hasAttachments: Boolean,
    currentQuality: AttachmentQuality,  // ADD
    onQualityClick: () -> Unit,         // ADD
    // ... existing params
) {
    Row {
        // Existing add button
        AddButton(...)

        // ADD: Quality indicator (only when attachments present)
        AnimatedVisibility(visible = hasAttachments) {
            QualityIndicatorButton(
                quality = currentQuality,
                onClick = onQualityClick
            )
        }
    }
}
```

**Attachments Side:**
- `QualitySelectionSheet` must accept current quality and return selection via callback
- Export `AttachmentQuality` enum for composer state

#### Task 4: Wire Edit Action to Thumbnails

**Composer Side:**
```kotlin
// In AttachmentThumbnail.kt - add edit overlay
@Composable
fun AttachmentThumbnail(
    attachment: AttachmentItem,
    onEdit: () -> Unit,   // ADD
    onRemove: () -> Unit,
    // ...
) {
    Box(modifier) {
        // ... thumbnail content ...

        // ADD: Edit button overlay
        IconButton(
            onClick = onEdit,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
        }
    }
}
```

**Attachments Side:**
- `AttachmentEditScreen` must accept URI and return edited URI via result
- Handle system intent fallback internally

#### Task 5: Unified State Model

Create shared attachment item model used by both systems:

```kotlin
// In data/model/AttachmentItem.kt (shared)
data class AttachmentItem(
    val id: String,
    val uri: Uri,
    val mimeType: String,
    val fileName: String?,
    val fileSize: Long,
    val width: Int?,
    val height: Int?,
    val quality: AttachmentQuality = AttachmentQuality.AUTO,
    val errorState: AttachmentErrorState? = null,
    val caption: String? = null,
    val thumbnailUri: Uri? = null
)

// Extension to convert from pending entity
fun PendingAttachmentEntity.toAttachmentItem(): AttachmentItem
```

### ComposerState Updates

```kotlin
// Updated ComposerState with attachment integration
@Immutable
data class ComposerState(
    // ... existing fields ...

    // Attachments (integrated with attachments system)
    val attachments: List<AttachmentItem> = emptyList(),
    val attachmentWarning: AttachmentWarning? = null,
    val attachmentQuality: AttachmentQuality = AttachmentQuality.AUTO,

    // Quality sheet
    val showQualitySheet: Boolean = false,

    // Edit mode
    val editingAttachment: AttachmentItem? = null
) {
    val hasAttachments: Boolean
        get() = attachments.isNotEmpty()

    val hasAttachmentErrors: Boolean
        get() = attachments.any { it.errorState != null }
}
```

### ComposerEvent Updates

```kotlin
sealed class ComposerEvent {
    // ... existing events ...

    // Attachment events (integrated with attachments system)
    data class MediaSelected(val uris: List<Uri>) : ComposerEvent()
    data class AttachmentRemoved(val item: AttachmentItem) : ComposerEvent()
    data class AttachmentRetry(val item: AttachmentItem) : ComposerEvent()
    data class AttachmentEdit(val item: AttachmentItem) : ComposerEvent()
    data class AttachmentEdited(val item: AttachmentItem, val newUri: Uri) : ComposerEvent()
    data class QualitySelected(val quality: AttachmentQuality) : ComposerEvent()
    object ShowQualitySheet : ComposerEvent()
    object HideQualitySheet : ComposerEvent()
}
```

### Testing the Integration

- [ ] Add attachment via Photo Picker → appears in thumbnail row
- [ ] Attachment with error → shows error overlay with retry button
- [ ] Tap retry → clears error and re-attempts operation
- [ ] Tap edit → opens edit screen, saves edits, updates thumbnail
- [ ] Change quality → persists to DataStore, applies on send
- [ ] Remove attachment → animates out of thumbnail row
- [ ] Send with attachments → compression applied per quality setting

---

## Testing Checklist

### Unit Tests

- [ ] ComposerState transitions
- [ ] TutorialState flow
- [ ] Gesture threshold calculations

### UI Tests

- [ ] Send button mode toggle
- [ ] Tutorial completion flow
- [ ] Attachment add/remove
- [ ] Smart reply selection
- [ ] Panel open/close
- [ ] Voice recording start/stop

### Manual Testing

- [ ] Animation smoothness on low-end device
- [ ] Keyboard show/hide transitions
- [ ] Orientation changes
- [ ] Dark/light theme
- [ ] Large font sizes
- [ ] TalkBack navigation

---

## Success Metrics

1. **User Familiarity**: Layout matches Google Messages mental model
2. **Animation Quality**: 60fps on Pixel 4a or equivalent
3. **Tutorial Completion**: >80% of new users complete tutorial
4. **Mode Switch Accuracy**: <5% accidental mode switches
5. **Accessibility**: WCAG 2.1 AA compliance

---

## Current Integration Status (December 2024)

### Components Complete ✅
All ChatComposer components have been implemented:
- `ChatComposer.kt` - Main orchestrating component
- `ComposerState.kt` - Unified state management
- `ComposerEvent.kt` - Event-driven architecture
- `ComposerTextField.kt`, `ComposerActionButtons.kt`, `ComposerMediaButtons.kt`
- `ComposerSendButton.kt` with mode toggle gesture
- `SendModeGestureHandler.kt` - Extracted gesture logic
- Tutorial system (`ComposerTutorial.kt`, `TutorialSpotlight.kt`, etc.)
- All panels (`MediaPickerPanel`, `EmojiKeyboardPanel`, `GifPickerPanel`, `VoiceMemoPanel`)
- Smart features (`SmartReplyRow`, `AttachmentThumbnailRow`, `ReplyPreviewBar`)
- Drawing tools (`DrawingCanvas`, `DrawingToolbar`, `TextOverlay`)
- `AttachmentEditScreen` with crop/rotate/draw/text/caption

### Integration Blocker 🚧
**ChatScreen still uses `ChatInputArea` instead of `ChatComposer`.**

The new ChatComposer is fully implemented but requires refactoring ChatScreen.kt to adopt it:

1. **State Migration**: ChatScreen manages voice recording state locally. Need to either:
   - Move state management to ChatViewModel
   - Create adapter layer to map local state to ComposerState

2. **Event Handling**: ChatComposer uses ComposerEvent sealed class. Need to map each event to existing ChatScreen/ChatViewModel logic.

3. **Testing Required**: Extensive testing needed after migration to ensure all features work (send, attachments, voice, mode toggle, tutorial, etc.)

### Remaining Phase 7 Tasks
- [ ] Migrate ChatScreen from ChatInputArea to ChatComposer
- [ ] Wire up remaining callbacks (quality selection sheet, edit attachment)
- [ ] Animation refinement and performance profiling
- [ ] Accessibility audit
- [ ] Remove old ChatInputArea.kt after migration

---

## References

- [Material Design 3 - Text Fields](https://m3.material.io/components/text-fields)
- [Material Design 3 - Motion](https://m3.material.io/styles/motion)
- [Google Messages APK Analysis](internal reference)
- [Jetpack Compose Animation Docs](https://developer.android.com/develop/ui/compose/animation)
