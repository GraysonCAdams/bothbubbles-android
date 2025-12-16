# Dependency Injection

## Purpose

Hilt modules for dependency injection. Provides all dependencies used throughout the application.

## Files

| File | Description |
|------|-------------|
| `AppModule.kt` | Application utilities (WorkManager, ImageLoader, context) |
| `CoroutinesModule.kt` | Coroutine dispatchers and `@ApplicationScope` |
| `DatabaseModule.kt` | Room database and all DAOs |
| `FcmModule.kt` | Firebase Cloud Messaging dependencies |
| `MessageSenderModule.kt` | Message sender strategy bindings |
| `NetworkModule.kt` | Retrofit, OkHttp, Moshi, API clients |
| `ServiceModule.kt` | Service interface bindings for testability |
| `SmsModule.kt` | SMS/MMS-specific dependencies |

## Architecture

```
Hilt Module Hierarchy:

@Singleton (Application-scoped)
├── AppModule           - App utilities
├── DatabaseModule      - Room database + DAOs
├── NetworkModule       - Retrofit + OkHttp
├── CoroutinesModule    - Dispatchers
├── ServiceModule       - Service bindings
├── MessageSenderModule - Sender strategies
├── FcmModule           - Firebase
└── SmsModule           - SMS dependencies
```

## Required Patterns

### Module Definition

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BothBubblesDatabase {
        return Room.databaseBuilder(
            context,
            BothBubblesDatabase::class.java,
            BothBubblesDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    fun provideMessageDao(database: BothBubblesDatabase): MessageDao {
        return database.messageDao()
    }
}
```

### Interface Bindings

For testability, bind implementations to interfaces:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindMessageSender(impl: MessageSendingService): MessageSender

    @Binds
    @Singleton
    abstract fun bindNotifier(impl: NotificationService): Notifier
}
```

### Qualifiers

Use qualifiers for multiple implementations:

```kotlin
// In CoroutinesModule
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

// Usage
@Provides
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
```

### Scoping

- `@Singleton` - Single instance for app lifetime
- `@ViewModelScoped` - Tied to ViewModel lifecycle
- `@ActivityScoped` - Tied to Activity lifecycle

## Module Responsibilities

| Module | Provides |
|--------|----------|
| `AppModule` | Context, WorkManager, ImageLoader |
| `DatabaseModule` | Database, all DAOs |
| `NetworkModule` | Retrofit, OkHttp, Moshi, APIs |
| `CoroutinesModule` | Dispatchers, ApplicationScope |
| `ServiceModule` | Interface → Implementation bindings |
| `FcmModule` | Firebase services |
| `SmsModule` | SMS/MMS services |

## Best Practices

1. Use `@Singleton` sparingly - only for truly shared instances
2. Prefer constructor injection over field injection
3. Use `@Binds` for interface bindings (more efficient than `@Provides`)
4. Group related providers in logical modules
5. Use qualifiers for disambiguation
6. Keep modules focused on single responsibility
