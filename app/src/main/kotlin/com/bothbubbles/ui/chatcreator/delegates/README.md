# Chat Creator Delegates

## Purpose

ViewModel delegates for chat creation screens. Follows the same delegate pattern as ChatViewModel.

## Files

| File | Description |
|------|-------------|
| `ChatCreationDelegate.kt` | Create chat and navigate |
| `ContactLoadDelegate.kt` | Load and organize contacts |
| `ContactSearchDelegate.kt` | Search and validate addresses |
| `RecipientSelectionDelegate.kt` | Manage selected recipients |

## Architecture

```
ChatCreatorViewModel
├── ContactLoadDelegate     - Load contacts from Android
├── ContactSearchDelegate   - Search and address validation
├── RecipientSelectionDelegate - Selected recipients state
└── ChatCreationDelegate    - Create chat via API
```

## Required Patterns

### Contact Loading

```kotlin
class ContactLoadDelegate @Inject constructor(
    private val contactsService: AndroidContactsService
) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            _contacts.value = contactsService.getAllContacts()
                .sortedBy { it.displayName }
        }
    }
}
```

### Address Validation

```kotlin
class ContactSearchDelegate @Inject constructor(
    private val iMessageService: IMessageAvailabilityService
) {
    suspend fun validateAddress(address: String): ValidationResult {
        return when {
            isValidPhoneNumber(address) -> {
                val isIMessage = iMessageService.isAvailable(address)
                ValidationResult.Valid(isIMessage)
            }
            isValidEmail(address) -> {
                val isIMessage = iMessageService.isAvailable(address)
                ValidationResult.Valid(isIMessage)
            }
            else -> ValidationResult.Invalid("Invalid address")
        }
    }
}
```

## Best Practices

1. Load contacts asynchronously
2. Cache contact list
3. Validate addresses before creation
4. Check iMessage availability
5. Handle permission denied gracefully
