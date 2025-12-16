# BothBubbles - Root Package

## Purpose

This is the root package for the BothBubbles Android application. It contains the application entry points and top-level configuration.

## Files

| File | Description |
|------|-------------|
| `BothBubblesApp.kt` | Hilt application class. Initializes WorkManager, Coil image loader, notification channels, SMS observers, contacts observers, shortcut service, and background sync. |
| `MainActivity.kt` | Single-activity entry point using Jetpack Compose navigation. |

## Architecture

BothBubbles follows Clean Architecture with three main layers:

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (ui/)                          │
│  Jetpack Compose screens, ViewModels, delegates             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Services Layer (services/)                │
│  Business logic, socket handling, messaging, sync           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Data Layer (data/)                       │
│  Repositories, Room database, API clients, preferences      │
└─────────────────────────────────────────────────────────────┘
```

## Required Patterns

### Application Initialization

All application-wide initialization should happen in `BothBubblesApp.onCreate()`:

```kotlin
@HiltAndroidApp
class BothBubblesApp : Application() {
    @Inject lateinit var myService: MyService

    override fun onCreate() {
        super.onCreate()
        // Initialize services here
    }
}
```

### Dependency Injection

- Use Hilt for all dependency injection
- Define modules in `di/` package
- Use `@Inject` constructor injection where possible

## Package Structure

| Package | Purpose |
|---------|---------|
| `data/` | Data layer - repositories, database, API, preferences |
| `di/` | Hilt dependency injection modules |
| `services/` | Services layer - business logic, messaging, sync |
| `ui/` | UI layer - screens, ViewModels, components |
| `util/` | Shared utilities |

## Documentation

The `docs/` directory (at repo root) contains important guidelines:

| Document | Purpose |
|----------|---------|
| `docs/COMPOSE_BEST_PRACTICES.md` | **MANDATORY** - Jetpack Compose performance rules and patterns |
| `docs/refactor_plans/` | Architecture refactor plans and conventions |

**Before writing Compose code**, read `docs/COMPOSE_BEST_PRACTICES.md` for required patterns including:
- Leaf-node state collection (push-down state)
- Immutable collections for UI state
- Stable callbacks
- Side-effect isolation

## Key Dependencies

- **Hilt**: Dependency injection
- **Coil**: Image loading with GIF/video support
- **WorkManager**: Background task scheduling
- **Room**: Local database
- **Retrofit**: REST API client
- **Socket.IO**: Real-time server communication
