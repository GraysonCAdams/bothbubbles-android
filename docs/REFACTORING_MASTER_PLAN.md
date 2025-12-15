# BlueBubbles Refactoring Master Plan

## Executive Summary

This document outlines the strategic roadmap for decomposing the monolithic classes in the BlueBubbles Android codebase. The goal is to improve maintainability, testability, and readability by applying established software design patterns.

## Architectural Principles

1.  **Single Responsibility Principle (SRP):** Each class or file should have one reason to change.
2.  **Composition over Inheritance:** Build complex UIs and logic by combining smaller, independent parts.
3.  **Unidirectional Data Flow (UDF):** State flows down, events flow up.
4.  **Delegate Pattern:** For ViewModels, delegate specific domains (e.g., Search, Sync) to helper classes.

---

## Phase 1: The Brain (ViewModel)

**Target:** `ChatViewModel.kt` (~3,000 lines)
**Pattern:** **Delegate Pattern**

The ViewModel is the central coordinator. Cleaning it first simplifies the data flow for the UI components.

### Strategy

Decompose the ViewModel into domain-specific delegates that hold their own state and logic. The ViewModel becomes a thin facade exposing these states.

### Action Plan

1.  **Composer Delegate:** Extract input, drafts, and attachment picking logic.
2.  **Message List Delegate:** Extract paging, data source, and list updates.
3.  **Connection Delegate:** Extract server connection and availability logic.
4.  **Scheduled Message Delegate:** Extract scheduling CRUD operations.

---

## Phase 2: The Input (Composer UI)

**Target:** `ChatInputArea.kt` (~1,300 lines)
**Pattern:** **State Hoisting & Component Extraction**

The input area is currently a mix of UI rendering and complex state management (audio recording, permission handling).

### Strategy

Split the monolithic `ChatInputArea` into distinct, stateless UI components. Move the complex state management into the `ChatComposerDelegate` (created in Phase 1).

### Action Plan

1.  **Extract Components:**
    - `ComposerTextField`: Pure text input handling.
    - `AudioRecorderPanel`: Visuals for recording state.
    - `AttachmentPicker`: The horizontal scroll of attachment options.
    - `SendButton`: Animation and state logic for the button itself.
2.  **Hoist State:** Ensure `ChatInputArea` receives a `ComposerState` object and emits `ComposerEvent`s, removing internal `remember` blocks for business logic.

---

## Phase 3: The Content (Attachments)

**Target:** `AttachmentContent.kt` (~2,400 lines)
**Pattern:** **Polymorphic UI (Factory Pattern)**

This file contains a massive `when` statement handling every file type, mixing video players, image loaders, and file previews.

### Strategy

Create a standard interface/signature for attachment renderers and create a separate Composable for each type. `AttachmentContent` becomes a simple dispatcher.

### Action Plan

1.  **Create Renderers:**
    - `VideoAttachment`: Encapsulate `ExoPlayer` logic.
    - `ImageAttachment`: Encapsulate `Coil` logic.
    - `AudioAttachment`: Encapsulate audio player logic.
    - `FileAttachment`: Generic file download/preview logic.
    - `ContactAttachment`: VCard rendering.
2.  **Refactor Dispatcher:** `AttachmentContent` simply checks the MIME type and calls the appropriate renderer.

---

## Phase 4: The View (Screen Layout)

**Target:** `ChatScreen.kt` (~2,200 lines)
**Pattern:** **Composition**

The main screen file acts as a container for everything, including dialogs, bottom sheets, and gesture handlers.

### Strategy

Break the screen into high-level structural components.

### Action Plan

1.  **Extract Structural Components:**
    - `ChatTopBar`: The header, back button, and avatar.
    - `ChatBackground`: The wallpaper/gradient logic.
    - `ChatDialogs`: Move all `AlertDialog` definitions to a separate file or distinct composables.
    - `ChatGestures`: Encapsulate complex gesture detectors (swipe to reply, etc.) into custom modifiers.

---

## Phase 5: The Engine (Services)

**Target:** `MessageSendingService.kt` (~1,200 lines)
**Pattern:** **Strategy Pattern**

This service handles sending via SMS, iMessage, scheduling, and retries, often with complex `if/else` chains.

### Strategy

Define a `MessageSender` interface and implement strategies for different transport methods.

### Action Plan

1.  **Define Interface:** `interface MessageSender { suspend fun send(message: Message) }`
2.  **Implement Strategies:**
    - `SmsSenderStrategy`: Handles Android SMSManager logic.
    - `IMessageSenderStrategy`: Handles API calls to the server.
    - `ScheduledSenderStrategy`: Handles deferring messages.
3.  **Context Class:** `MessageSendingService` selects the correct strategy based on the chat type and executes it.

---

## Execution Order

1.  **ChatViewModel (Phase 1)** - _In Progress_
2.  **ChatInputArea (Phase 2)** - _Dependent on Phase 1_
3.  **AttachmentContent (Phase 3)** - _Independent, can be parallelized_
4.  **ChatScreen (Phase 4)** - _Continuous cleanup_
5.  **MessageSendingService (Phase 5)** - _Independent backend task_
