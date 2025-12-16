# Composer Tutorial

## Purpose

Onboarding tutorial overlays for new users to learn composer features.

## Files

| File | Description |
|------|-------------|
| `ComposerTutorial.kt` | Main tutorial orchestration |
| `SendModeTutorialStep.kt` | Tutorial for iMessage/SMS toggle |
| `TutorialSpotlight.kt` | Spotlight effect for highlighting UI |
| `TutorialState.kt` | Tutorial progress state |

## Required Patterns

### Tutorial Spotlight

```kotlin
@Composable
fun TutorialSpotlight(
    targetBounds: Rect,
    message: String,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Dim background except target area
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.7f))
            drawOval(
                color = Color.Transparent,
                topLeft = targetBounds.topLeft,
                size = targetBounds.size,
                blendMode = BlendMode.Clear
            )
        }

        // Tooltip
        TooltipBubble(
            message = message,
            position = targetBounds.bottomCenter,
            onDismiss = onDismiss
        )
    }
}
```

### Tutorial State

```kotlin
data class TutorialState(
    val currentStep: TutorialStep?,
    val completedSteps: Set<TutorialStep>
)

enum class TutorialStep {
    SEND_MODE_TOGGLE,
    ATTACHMENT_PICKER,
    EFFECTS_PICKER,
    VOICE_MEMO
}
```

## Best Practices

1. Show tutorial once per user (persist completion)
2. Allow skipping tutorial
3. Highlight one feature at a time
4. Use clear, concise instructions
5. Support accessibility for tutorial
