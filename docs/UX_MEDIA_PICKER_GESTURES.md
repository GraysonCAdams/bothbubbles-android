# Media Picker Gestures & Drag Handler Architecture

## Overview

This document describes the gesture handling and drag-to-dismiss behavior across all media picker panel experiences in the BothBubbles chat composer.

---

## Panel Types

The composer supports three expandable panels:

| Panel              | Purpose                                                                            | Access Point                   |
| ------------------ | ---------------------------------------------------------------------------------- | ------------------------------ |
| **Media Picker**   | Grid of attachment options (Gallery, Camera, GIF, Files, Location, Audio, Contact) | Tap the `+` button in composer |
| **Emoji Keyboard** | Emoji category tabs + emoji grid                                                   | Tap the emoji icon in composer |
| **GIF Picker**     | Search bar + GIF results grid                                                      | Tap "GIF" in Media Picker      |

---

## Visual Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  [Attachment Thumbnails Row - if attachments selected]       │
├──────────────────────────────────────────────────────────────┤
│  [Reply Preview Bar - if replying to message]                │
├──────────────────────────────────────────────────────────────┤
│ ╭────────────────────────────────────────────────────────╮   │
│ │ [+] │ Message text input...           [📷] [😊] [🖼]  │   │
│ ╰────────────────────────────────────────────────────────╯ ▶ │  <- Main Input Row
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │                 [ ══ Handle ══ ]                       │  │  <- Unified Drag Handle (Inside Panel)
│  │                                                        │  │
│  │  [ Universal Content Area ]                            │  │
│  │  - Renders Media, Emoji, OR GIF content                │  │
│  │  - Expands to fill space if keyboard is hidden         │  │
│  │                                                        │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## Drag-to-Dismiss Behavior

### Unified Gesture Handler (ComposerPanelHost Level)

A single, unified drag handler manages dismiss gestures for **ALL** panels (Media Picker, Emoji Keyboard, and GIF Picker). This handler is located in `ComposerPanelHost.kt` and provides:

**Behavior:**

- Drag the handle **downward** to dismiss
- The **input row AND panel move together** during drag (creating a connected sheet effect)
- Releasing past **120dp threshold** dismisses the panel
- Releasing before threshold animates back to position
- **Spring animation** for smooth, natural feel

**Code Location:** `ComposerPanelHost.kt` (wrapping content)

```
User drags handle → dragOffset increases → Input Row + Panel offset down
                                                       ↓
User releases → if dragOffset > 120dp → dismiss panel
             → else → animate back to 0
```

### Universal Expansion Behavior

All panels now support expanding to fill available space when the keyboard is hidden.

**Behavior:**

1. **Keyboard Visible:** Panel height matches keyboard height (or base height).
2. **Keyboard Hidden:** Panel expands to fill the available space (e.g., +200dp or full screen).
3. **Transition:** Smooth spring animation between states.

---

## Panel-by-Panel UX

### 1. Media Picker Panel

**Entry Animation:**

- Slide up from bottom with spring physics
- Fade in over 150ms

**Exit Animation:**

- Slide down with **Emphasized Accelerate** easing (200ms)
- Fade out over 150ms

**Dismiss Methods:**

- Drag handle swipe down (unified handler)
- Select an option (auto-dismiss for most options)
- GIF selection opens GIF Picker instead

**Visual:**

```
┌─────────────────────────────────────────────┐
│                 [ ══ Handle ══ ]            │  <- Part of Panel
├─────────────────────────────────────────────┤
│                                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐     │
│  │ Gallery │  │ Camera  │  │   GIF   │ ... │
│  └─────────┘  └─────────┘  └─────────┘     │
│                                             │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐     │
│  │  Files  │  │Location │  │  Audio  │ ... │
│  └─────────┘  └─────────┘  └─────────┘     │
│                                             │
└─────────────────────────────────────────────┘
```

---

### 2. Emoji Keyboard Panel

**Entry Animation:**

- Slide up from bottom with spring physics
- Fade in over 150ms

**Exit Animation:**

- Slide down with **Emphasized Accelerate** easing (200ms)
- Fade out over 150ms

**Dismiss Methods:**

- Drag handle swipe down (unified handler)
- Open different panel (keyboard, media picker)

**Visual:**

```
┌─────────────────────────────────────────────┐
│                 [ ══ Handle ══ ]            │  <- Part of Panel
├─────────────────────────────────────────────┤
│                                             │
│ [😊][👍][❤️][🐶][🍎][⚽][✈️][💡][#️⃣]        │  <- Category tabs
│ ───────────────────────────────────────────│
│                                             │
│  😀 😃 😄 😁 😆 😅 🤣 😂                   │
│  🙂 🙃 😉 😊 😇 🥰 😍 🤩                   │  <- Emoji grid
│  😘 😗 😚 😙 🥲 😋 😛 😜                   │
│  ...                                        │
│                                             │
└─────────────────────────────────────────────┘
```

---

### 3. GIF Picker Panel

**Entry Animation:**

- Slide up from bottom with spring physics
- Fade in over 150ms
- Search field auto-focuses and keyboard opens

**Exit Animation:**

- Slide down with **Emphasized Accelerate** easing (200ms)
- Fade out over 150ms

**Dismiss Methods:**

- Drag handle swipe down (unified handler)
- Select a GIF (auto-dismiss)

**Special Behaviors:**

1. **Keyboard Dismissal on Scroll:**
   - When user scrolls the GIF grid ~100dp, keyboard automatically hides
   - Panel stays open for continued browsing (and expands)

**Visual (Keyboard Visible):**

```
┌─────────────────────────────────────────────┐
│                 [ ══ Handle ══ ]            │  <- Part of Panel
├─────────────────────────────────────────────┤
│  ╭────────────────────────────────────────╮ │
│  │ 🔍 Search GIFs                    [✕]  │ │  <- Search bar (auto-focused)
│  ╰────────────────────────────────────────╯ │
├─────────────────────────────────────────────┤
│  ┌───────┐  ┌───────┐  ┌───────┐           │
│  │  GIF  │  │  GIF  │  │  GIF  │           │  <- GIF grid (base height)
│  └───────┘  └───────┘  └───────┘           │
│  ┌───────┐  ┌───────┐  ┌───────┐           │
│  │  GIF  │  │  GIF  │  │  GIF  │           │
│  └───────┘  └───────┘  └───────┘           │
│  ┌───────┐  ┌───────┐  ┌───────┐           │
│  │  GIF  │  │  GIF  │  │  GIF  │           │
│  └───────┘  └───────┘  └───────┘           │
└─────────────────────────────────────────────┘
              [  KEYBOARD  ]
```

---

## Animation Specifications

### Spring Physics (Used for Panel Entry & Drag)

| Parameter     | Value | Effect                         |
| ------------- | ----- | ------------------------------ |
| Damping Ratio | 0.7   | Medium bounce, settles quickly |
| Stiffness     | 400   | Responsive feel                |

### Timing (Used for Panel Exit)

| Animation    | Duration | Easing                    |
| ------------ | -------- | ------------------------- |
| Fast fade    | 150ms    | Linear                    |
| Normal slide | 200ms    | **Emphasized Accelerate** |
| Instant      | 0ms      | -                         |

### Dismiss Threshold

| Measurement          | Value                      |
| -------------------- | -------------------------- |
| Drag threshold       | 120dp                      |
| GIF scroll threshold | ~100dp (300px accumulated) |

---

## Component File Locations

| Component                       | File Path                                             |
| ------------------------------- | ----------------------------------------------------- |
| ChatComposer (parent container) | `ui/chat/composer/ChatComposer.kt`                    |
| ComposerPanelHost (drag logic)  | `ui/chat/composer/panels/ComposerPanelHost.kt`        |
| PanelDragHandle (visual)        | `ui/chat/composer/components/PanelDragHandle.kt`      |
| MediaPickerPanel                | `ui/chat/composer/panels/MediaPickerPanel.kt`         |
| EmojiKeyboardPanel              | `ui/chat/composer/panels/EmojiKeyboardPanel.kt`       |
| GifPickerPanel                  | `ui/chat/composer/panels/GifPickerPanel.kt`           |
| Motion Tokens                   | `ui/chat/composer/animations/ComposerMotionTokens.kt` |

---

## Design Decisions Summary

1. **Unified drag handling** for ALL panels ensures consistent UX.
2. **Input row moves with panel** during drag - creates sense of connected interface.
3. **Unified Drag Handle** component placed inside every panel for visual consistency (MD3).
4. **Universal Height Expansion** maximizes browsing space for all content types when keyboard is hidden.
5. **Emphasized Accelerate** easing for exits makes dismissal feel more natural and responsive.
6. **Scroll-triggered keyboard dismiss** in GIF picker prevents accidental keyboard interference while browsing.
