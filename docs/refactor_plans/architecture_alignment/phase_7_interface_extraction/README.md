# Phase 7a — Interface Extraction & Safety Nets

> **Status**: Ready to Start
> **Focus**: Testability & Decoupling
> **Sibling Phase**: [Phase 7b — Future Scope](../phase_7_future_scope/README.md) depends on these interfaces being available.

## Layman's Explanation

Before we refactor complex screens like the Conversation List, we need to make sure the tools they use (like "Send Message" or "Export Contacts") are safe to touch.

This phase is about creating "safety handles" (interfaces) for the remaining concrete services. This allows us to write tests that don't accidentally send real SMS messages or wipe the database and unblocks the migrations outlined in Phase 7b.

## Goals

1.  **Extract Interfaces**: Create interfaces for remaining concrete services.
2.  **Enable Fake Injection**: Ensure Hilt modules can swap these for Fakes in tests.
3.  **Safety Net Tests**: Write basic tests for these interfaces to ensure they behave as expected.

## Targets

| Concrete Class | New Interface | Why? |
| :--- | :--- | :--- |
| `PendingMessageRepository` | `PendingMessageSource` | Critical for testing message queue logic without a real DB. |
| `VCardService` | `VCardExporter` | Decouples contact export logic from Android Context. |
| `ContactBlockingService` | `ContactBlocker` | Allows testing blocking logic without modifying system contacts. |
| `SmsRestoreService` | `SmsRestorer` | Dangerous operation; needs strict interface boundary. |
| `SoundManager` | `SoundPlayer` | Decouples sound playback for testability (used by SettingsViewModel). |

## Exit Criteria

- [ ] `PendingMessageSource` interface created and used.
- [ ] `VCardExporter` interface created and used.
- [ ] `ContactBlocker` interface created and used.
- [ ] `SmsRestorer` interface created and used.
- [ ] `SoundPlayer` interface created and used.
- [ ] All usages in `ConversationsViewModel`, `SetupViewModel`, and related delegates updated to use interfaces (the Phase 7b work can only start when these are checked).
