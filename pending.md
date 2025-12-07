# Pending Features & Technical Debt

## Planned Features

### Medium Priority

| Feature                 | Description                 | Status      |
| ----------------------- | --------------------------- | ----------- |
| **Message Translation** | Translate incoming messages | Not started |
| **Home Screen Widgets** | Conversation/unread widgets | Not started |

### Lower Priority

| Feature                 | Description               | Status      |
| ----------------------- | ------------------------- | ----------- |
| **Wear OS Support**     | Smartwatch companion app  | Not started |
| **RCS Messaging**       | Enhanced SMS features     | Not started |
| **Message Annotations** | Private notes on messages | Not started |
| **Voice/Video Calls**   | Native calling support    | Partial (dial-out via third-party apps) |

---

## Technical Debt

### Unused Server API Endpoints

Defined in `BlueBubblesApi.kt` but never called:

| Endpoint          | Purpose               |
| ----------------- | --------------------- |
| `queryHandles()`  | Query contact handles |
| `getHandle()`     | Get single handle     |
| `getContacts()`   | Fetch server contacts |
| `getMessage()`    | Get single message    |
| `deleteMessage()` | Delete a message      |
| `getAttachment()` | Get single attachment |

---

## TODO Locations

<details>
<summary>Click to expand full TODO list</summary>

**ChatScreen.kt**

- `:852` - Custom emoji reactions (blocked: BlueBubbles server only supports 6 standard tapbacks)

</details>

---

## Recently Completed

Features implemented in recent development:

- Phone/Video Call Handlers (ConversationDetailsScreen - opens dialer/video call chooser)
- Contact Blocking via native Android BlockedNumberContract (AndroidContactsService, ConversationDetailsScreen)
- Participant Quick Actions Popup (ConversationDetailsScreen - call, message, view contact)
- Link Click Handler (MediaLinksScreen - opens URLs in browser)
- Scheduled Message Sending (ScheduledMessageWorker, ScheduledMessageDao, ChatViewModel - client-side via WorkManager)
- Conversation Snooze (ui/components/SnoozeDuration.kt, SnoozeDurationDialog.kt)
- FCM Push Notifications (services/fcm/)
- Emoji Picker (ui/components/EmojiPickerPanel.kt)
- Conversation Categories (services/categorization/, ui/settings/categorization/)
- Message Backup/Export (services/export/, ui/settings/export/)
- Open Source Licenses Screen (ui/settings/about/OpenSourceLicensesScreen.kt)
- Smart Reply Service (services/smartreply/, ui/components/SmartReplyChips.kt)
- Quick Reply Templates (ui/settings/templates/)
- Spam Settings (ui/settings/spam/)
- Contacts Service (services/contacts/)
- Share Sheet (ui/share/)
- Forward Message Dialog (ui/components/ForwardMessageDialog.kt)
- VCard Options Dialog (ui/components/VCardOptionsDialog.kt)
- Sender name resolution from handles (ChatViewModel)
- Effect Playback Tracking (ChatViewModel, ChatScreen - bubble/screen effects with datePlayed persistence)
- Places filter for search (ConversationsScreen)
- Group avatar participant names (ConversationsScreen)
- In-conversation search from ChatDetails (NavHost)
- Open URLs in browser from LinksScreen (NavHost)
- Open place details from PlacesScreen (NavHost)
- Link extraction from messages (MediaLinksViewModel)
- Contact favorites detection (ChatCreatorViewModel)

---

## Notes

- Follow existing patterns: MVVM, Repository pattern, Hilt DI
- Some features require server API support
- Many features need Android permissions
- Stack: Jetpack Compose + Material 3, Room, Retrofit, Socket.IO
- BlueBubbles server does not have a blocking API - contact blocking uses native Android BlockedNumberContract
- BlueBubbles server only supports 6 standard tapback reactions (love, like, dislike, laugh, emphasize, question) - custom emoji reactions require server changes
