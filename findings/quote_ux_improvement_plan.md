# Quote / Reply Message UX Improvement Plan

## Current State Analysis

### Composition (ReplyPreviewBar)
- Simple horizontal bar above composer input
- Left accent bar (primary color) + "Replying to [Name]" + text preview + dismiss button
- Attachments show only text placeholder "Attachment"
- No thumbnail preview for images/videos
- Single-line truncated text

### Viewing (ReplyQuoteIndicator)
- Rounded surface chip above message bubble
- Colored accent bar + sender name + text preview (1 line, 50 chars max)
- "[Attachment]" placeholder when no text - no thumbnail
- "Tap to view thread" when original not found
- Tap opens thread overlay

---

## Proposed UX Improvements

### Priority 1: Rich Attachment Previews in Quotes

**Problem**: When quoting a message with an image/video, users see only "[Attachment]" with no visual context.

**Solution**: Show small thumbnails in quote previews.

#### Composition (ReplyPreviewBar)
```
┌──────────────────────────────────────────────────────────────────┐
│ █ Replying to John                                            [X]│
│ █ [IMG]  Check out this sunset!                                  │
│ █  40px                                                          │
└──────────────────────────────────────────────────────────────────┘
```

- Add 40x40dp thumbnail on the left of preview text
- For videos: show thumbnail with small play icon overlay
- For audio: show waveform icon
- For documents: show document type icon
- Fall back to file type icon if thumbnail unavailable

#### Viewing (ReplyQuoteIndicator)
```
┌─────────────────────────────────────────────┐
│ █  John             ┌──────┐                │
│ █  Check out this   │ IMG  │                │
│ █  sunset!          │ 48px │                │
│                     └──────┘                │
└─────────────────────────────────────────────┘
```

- Add 48x48dp thumbnail on the right side of quote chip
- Rounded corners matching quote container
- For multiple attachments: show first with "+N" badge

**Data Changes**:
- Extend `ReplyPreviewData` with `thumbnailUri: String?`
- Extend `MessagePreview` with `thumbnailUri: String?`
- Fetch attachment thumbnail path when building reply preview

---

### Priority 2: Scroll-to-Original vs Thread Overlay

**Problem**: Tapping a quote always opens thread overlay. Sometimes users just want to see the original message in context.

**Solution**: Dual interaction model.

| Gesture | Action |
|---------|--------|
| **Tap** | Smooth scroll to original message in chat (if loaded) + brief highlight animation |
| **Long-press** | Open thread overlay (current behavior) |

#### Implementation
```kotlin
// ReplyQuoteIndicator
onClick = { onScrollToOriginal(replyPreview.originalGuid) }
onLongClick = { onLoadThread(replyPreview.originalGuid) }
```

- When tapping: find original in list, scroll with animation, apply 1-second highlight pulse
- If original not loaded: show toast "Message not in view" and offer to load thread
- Long-press opens full thread overlay (existing behavior)

---

### Priority 3: Quote Discovery - Long-Press Context Menu

**Problem**: Reply is only discoverable via swipe gesture. Many users don't discover swipe actions.

**Solution**: Add "Reply" option to message focus/context menu.

#### Current Focus Menu Actions:
- Copy
- Forward
- Delete
- React (tapbacks)

#### Add "Reply" Action:
```
┌─────────────────────────────────────────┐
│  ↩ Reply                                │
│  📋 Copy                                │
│  ↪ Forward                              │
│  🗑 Delete                              │
└─────────────────────────────────────────┘
```

- "Reply" at top of menu (most common action after reactions)
- Same behavior as swipe-to-reply
- Icon: `Icons.Default.Reply` or curved arrow

**Files to modify**:
- `FocusMenuCard.kt` - Add Reply action
- `MessageFocusOverlay.kt` - Wire up callback
- `ChatViewModel` / `ChatSendDelegate` - Already has `setReplyTo()`

---

### Priority 4: Quoted Quote Indicator (Nested Replies)

**Problem**: When quoting a message that is itself a reply, there's no visual indication of the quote chain.

**Solution**: Show nested quote depth indicator.

#### Visual Design
```
┌─────────────────────────────────────────────────┐
│ █ █ Sarah → You                                 │
│ █ █ "Yeah I saw that too"                       │
└─────────────────────────────────────────────────┘
```

- Double accent bar for 2-level deep quote
- Show "A → B" for quote chain (who quoted whom)
- Max display depth: 2 levels (collapse deeper chains)

**Data Changes**:
- Add `quotedQuoteDepth: Int` to `ReplyPreviewData`
- When original message has `threadOriginatorGuid`, increment depth

---

### Priority 5: Inline Quote Expansion

**Problem**: Long quoted messages are truncated to 1 line, losing important context.

**Solution**: Allow expanding quote inline.

#### Collapsed State (default)
```
┌─────────────────────────────────────────────────┐
│ █ John                                    ▼     │
│ █ This is a really long message that g...       │
└─────────────────────────────────────────────────┘
```

#### Expanded State (on tap or chevron click)
```
┌─────────────────────────────────────────────────┐
│ █ John                                    ▲     │
│ █ This is a really long message that goes on    │
│ █ for multiple lines and now you can see all    │
│ █ of it when expanded.                          │
└─────────────────────────────────────────────────┘
```

- Chevron indicator for expandable quotes (only shown if text > 50 chars)
- Single tap: toggle expansion
- Expansion animates smoothly with `animateContentSize()`
- Long-press: opens thread overlay (unchanged)

**Data Changes**:
- Add `fullText: String?` to `ReplyPreviewData` (or load on demand)
- Track expansion state per message in UI layer

---

### Priority 6: Accessibility Improvements

**Problem**: Screen readers don't provide good context for quoted content.

**Solution**: Enhanced content descriptions.

#### Composition
```kotlin
Modifier.semantics {
    contentDescription = "Replying to ${senderName}. Original message: ${previewText}"
    stateDescription = "Swipe left to dismiss"
}
```

#### Viewing
```kotlin
Modifier.semantics {
    contentDescription = "Quoted message from ${senderName}: ${previewText}. Tap to scroll to original, hold to view thread."
    role = Role.Button
}
```

- Clear descriptions of quote source and content
- Announce available actions
- Custom actions for expand/collapse

---

### Priority 7: Multi-Message Quoting (Future)

**Problem**: Can only reply to one message at a time.

**Solution**: Support selecting and quoting multiple messages.

#### Composition UI
```
┌──────────────────────────────────────────────────────────────────┐
│ █ Replying to 3 messages                                      [X]│
│ █ John: "First message..."                                       │
│ █ Sarah: "Second message..."                                     │
│ █ John: "Third message..."                                       │
└──────────────────────────────────────────────────────────────────┘
```

**Note**: This requires server-side support for multi-reply threads. Defer until BlueBubbles server supports it.

---

### Priority 8: Quick Reply from Notification

**Problem**: Replying from notification doesn't preserve quote context.

**Solution**: When quick-replying from notification, include quote reference.

- Direct reply from notification includes `threadOriginatorGuid`
- User's response appears as a reply to the message that triggered notification
- Existing notification action infrastructure can be extended

---

## Implementation Order

| Phase | Features | Effort | Impact |
|-------|----------|--------|--------|
| **1** | Long-press Reply in context menu | Small | High (discoverability) |
| **2** | Scroll-to-original on tap | Medium | High (navigation) |
| **3** | Attachment thumbnails in quotes | Medium | High (visual clarity) |
| **4** | Accessibility improvements | Small | Medium (inclusivity) |
| **5** | Inline quote expansion | Medium | Medium (context) |
| **6** | Nested quote indicator | Small | Low (edge case) |
| **7** | Quick reply with quote | Medium | Medium (convenience) |
| **8** | Multi-message quoting | Large | Low (depends on server) |

---

## File Impact Summary

| File | Changes |
|------|---------|
| `FocusMenuCard.kt` | Add Reply action |
| `MessageFocusOverlay.kt` | Wire Reply callback |
| `ReplyPreviewBar.kt` | Add thumbnail, improve accessibility |
| `ReplyQuoteIndicator.kt` | Add thumbnail, expand/collapse, scroll-vs-thread |
| `MessageModels.kt` | Extend `ReplyPreviewData` with thumbnail, depth |
| `ComposerState.kt` | Extend `MessagePreview` with thumbnail |
| `CursorChatMessageListDelegate.kt` | Load thumbnail URIs, compute quote depth |
| `ChatMessageList.kt` | Handle scroll-to-message with highlight |
| `ChatViewModel.kt` | Scroll-to-message method |

---

## Design Principles

1. **Progressive disclosure** - Simple by default, details on demand (expand/thread)
2. **Familiar patterns** - Match iOS iMessage quote behavior where possible
3. **Visual hierarchy** - Quote is subordinate to message, not competing for attention
4. **Graceful degradation** - Works even when original message is deleted
5. **Performance** - Thumbnail loading should not block message list rendering
