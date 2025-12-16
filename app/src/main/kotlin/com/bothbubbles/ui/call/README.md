# Call UI

## Purpose

UI for incoming FaceTime call notifications and call handling.

## Files

| File | Description |
|------|-------------|
| `IncomingCallActivity.kt` | Full-screen incoming call UI |

## Architecture

```
Incoming Call Flow:

Server Push (incoming FaceTime) → IncomingCallActivity
                               → Show caller info
                               → Accept → Open FaceTime link
                               → Decline → Dismiss
```

## Required Patterns

### Incoming Call Activity

```kotlin
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME)
        val callLink = intent.getStringExtra(EXTRA_CALL_LINK)

        setContent {
            IncomingCallScreen(
                callerName = callerName,
                onAccept = { openFaceTimeLink(callLink) },
                onDecline = { finish() }
            )
        }
    }
}
```

## Best Practices

1. Show over lock screen
2. Turn screen on for incoming calls
3. Handle both answer and decline actions
4. Display caller photo/name clearly
5. Support accessibility
