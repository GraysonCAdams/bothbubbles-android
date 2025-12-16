# Phase 9 — Setup & Onboarding Architecture

> **Status**: Planned
> **Prerequisite**: Phase 7 (Interface Extraction)

## Layman's Explanation

The "Setup" flow is critical for new users. It involves complex state management (permissions, server connection, sync). Currently, it uses a mix of manual dependency injection and older patterns.

This phase modernizes the Setup flow to ensure it is robust, testable, and easy to maintain.

## Goals

1.  **Hilt Injection**: Ensure `SetupViewModel` and its delegates use Hilt for all dependencies.
2.  **State Management**: Unify setup state into a single `SetupUiState` flow.
3.  **Error Handling**: Standardize error handling across the setup steps.

## Targets

- `SetupViewModel`
- `QRCodeScannerDelegate`
- `ServerConnectionDelegate`
- `PermissionRequestDelegate`
- `SyncInitializationDelegate`

## Delegate Inventory

| Component | Pain Today | Planned Fix | Owner | Status |
|-----------|------------|-------------|-------|--------|
| `SetupViewModel` | Mixed manual construction + implicit state | Convert to single `SetupUiState` + injected delegates | _Unassigned_ | ☐ |
| `QRCodeScannerDelegate` | Emits results via callbacks | Provide `SharedFlow<ScanEvent>` | _Unassigned_ | ☐ |
| `ServerConnectionDelegate` | Talks directly to `SocketService` | Depend on `SocketConnection`, emit `ServerState` flow | _Unassigned_ | ☐ |
| `PermissionRequestDelegate` | Couples UI + logic | Expose `StateFlow<PermissionStatus>` | _Unassigned_ | ☐ |
| `SyncInitializationDelegate` | Hard to test, no DI hooks | Inject dependencies, report progress via `StateFlow<SyncStatus>` | _Unassigned_ | ☐ |

## Exit Criteria

- [ ] `SetupViewModel` fully Hilt-injected.
- [ ] Setup steps modeled as a state machine.
- [ ] UI observes a single `SetupUiState`.
- [ ] Network operations use the `SocketConnection` interface (from Phase 2).
- [ ] Inventory table above checked off with owners assigned.
