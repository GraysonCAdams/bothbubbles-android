# Phase 7a: Interface Extraction — Implementation Plan

> **Status**: Ready to Start
> **Goal**: Enable safe testing by decoupling dangerous operations from concrete classes so Phase 7b migrations can proceed.
> **Feeds Into**: [Phase 7b — Future Scope](../phase_7_future_scope/impl/README.md)

## Overview

This phase focuses on extracting interfaces for services that perform "dangerous" or side-effect-heavy operations (DB writes, SMS sending, Contact modification). This allows us to inject "Fake" implementations during tests.

## Targets

| Concrete Class | New Interface | Location |
| :--- | :--- | :--- |
| `PendingMessageRepository` | `PendingMessageSource` | `com.bothbubbles.data.repository` |
| `VCardService` | `VCardExporter` | `com.bothbubbles.services.contacts` |
| `ContactBlockingService` | `ContactBlocker` | `com.bothbubbles.services.contacts` |
| `SmsRestoreService` | `SmsRestorer` | `com.bothbubbles.services.export` |
| `SoundManager` | `SoundPlayer` | `com.bothbubbles.services.sound` |

## Step-by-Step Implementation

### Step 1: Extract `PendingMessageSource`

1.  Create interface `PendingMessageSource` in `com.bothbubbles.data.repository`.
2.  Copy public method signatures from `PendingMessageRepository`.
3.  Make `PendingMessageRepository` implement `PendingMessageSource`.
4.  Bind in `RepositoryModule`:
    ```kotlin
    @Binds
    abstract fun bindPendingMessageSource(impl: PendingMessageRepository): PendingMessageSource
    ```
5.  Replace usage in `ChatSendDelegate`, `MessageSendWorker`, and any ViewModels listed as Phase 7b dependencies.

### Step 2: Extract `VCardExporter`

1.  Create interface `VCardExporter` in `com.bothbubbles.services.contacts`.
2.  Define method: `suspend fun exportContacts(uri: Uri): Result<Int>`
3.  Make `VCardService` implement `VCardExporter`.
4.  Bind in `ServiceModule`.
5.  Replace usage in `SettingsViewModel`, `ChatComposerDelegate`, and any other modules called out in the Phase 7b backlog.

### Step 3: Extract `ContactBlocker`

1.  Create interface `ContactBlocker`.
2.  Define methods: `blockContact(address: String)`, `unblockContact(address: String)`.
3.  Make `ContactBlockingService` implement `ContactBlocker`.
4.  Bind in `ServiceModule`.
5.  Replace usage in `ChatOperationsDelegate`.

### Step 4: Extract `SmsRestorer`

1.  Create interface `SmsRestorer`.
2.  Define method: `suspend fun restoreSms(backupFile: File): Flow<RestoreProgress>`
3.  Make `SmsRestoreService` implement `SmsRestorer`.
4.  Bind in `ServiceModule`.

### Step 5: Extract `SoundPlayer`

1.  Create interface `SoundPlayer` in `com.bothbubbles.services.sound`.
2.  Define methods: `playNotificationSound()`, `playMessageSentSound()`, etc.
3.  Make `SoundManager` implement `SoundPlayer`.
4.  Bind in `ServiceModule`:
    ```kotlin
    @Binds
    abstract fun bindSoundPlayer(impl: SoundManager): SoundPlayer
    ```
5.  Replace usage in `SettingsViewModel` and any other UI modules.

## Verification

*   Run `grep` to ensure no UI code imports the concrete classes (except for the Hilt modules).
*   Build the app to ensure DI graph is valid.
