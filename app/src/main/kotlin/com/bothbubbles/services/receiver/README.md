# Broadcast Receivers

## Purpose

Android broadcast receivers for system events like device boot and package updates.

## Files

| File | Description |
|------|-------------|
| `BootReceiver.kt` | Handle device boot to restart services |

## Architecture

```
Boot Sequence:

Device Boot → BootReceiver.onReceive()
           → Check if setup complete
           → Start SocketForegroundService
           → Schedule background sync
           → Re-enqueue pending messages
```

## Required Patterns

### Boot Receiver

```kotlin
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val setupComplete = settingsDataStore.isSetupComplete.first()
                if (!setupComplete) return@launch

                // Start foreground service
                SocketForegroundService.start(context)

                // Schedule background sync
                BackgroundSyncWorker.schedule(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

### Manifest Declaration

```xml
<receiver
    android:name=".services.receiver.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

## Best Practices

1. Use `goAsync()` for long-running operations
2. Check setup state before starting services
3. Handle both regular boot and quick boot
4. Keep receiver logic minimal
5. Delegate to services for actual work
