# Shortcut Service

## Purpose

Manage Android shortcuts for the app. Publishes recent conversations as share targets in the system share sheet.

## Files

| File | Description |
|------|-------------|
| `ShortcutService.kt` | Manage dynamic and sharing shortcuts |

## Architecture

```
Shortcut Flow:

ConversationUpdate → ShortcutService
                  → Get top N recent chats
                  → Build ShortcutInfo for each
                  → Publish via ShortcutManager

Share Sheet Flow:

External App Share → Android Share Sheet
                  → Shows recent conversation shortcuts
                  → User selects conversation
                  → Opens app with share intent
```

## Required Patterns

### Shortcut Service

```kotlin
class ShortcutService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val shortcutManager = context.getSystemService(ShortcutManager::class.java)

    fun startObserving() {
        scope.launch {
            chatRepository.getRecentChats(limit = 5)
                .collect { chats ->
                    updateShortcuts(chats)
                }
        }
    }

    private fun updateShortcuts(chats: List<Chat>) {
        val shortcuts = chats.map { chat ->
            ShortcutInfo.Builder(context, "chat_${chat.guid}")
                .setShortLabel(chat.displayName)
                .setLongLabel(chat.displayName)
                .setIcon(buildIcon(chat))
                .setIntent(buildIntent(chat))
                .setCategories(setOf(CATEGORY_SHARE_TARGET))
                .setLongLived(true)
                .build()
        }

        shortcutManager.dynamicShortcuts = shortcuts
    }

    private fun buildIntent(chat: Chat): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(EXTRA_CHAT_GUID, chat.guid)
        }
    }
}
```

### Share Target Declaration

```xml
<!-- In res/xml/shortcuts.xml -->
<shortcuts>
    <share-target android:targetClass="com.bothbubbles.MainActivity">
        <data android:mimeType="text/*" />
        <data android:mimeType="image/*" />
        <category android:name="com.bothbubbles.SHARE_TARGET" />
    </share-target>
</shortcuts>
```

## Best Practices

1. Limit shortcuts to 5 (system recommendation)
2. Use meaningful labels and icons
3. Mark shortcuts as long-lived for share targets
4. Update shortcuts when conversations change
5. Handle share intent in MainActivity
