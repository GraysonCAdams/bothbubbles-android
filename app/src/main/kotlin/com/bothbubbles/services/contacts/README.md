# Contacts Service

## Purpose

Android contacts integration for resolving contact names, photos, and generating vCards. Also handles contact blocking.

## Files

| File | Description |
|------|-------------|
| `AndroidContactsService.kt` | Query Android contacts by phone/email |
| `ContactBlockingService.kt` | Block/unblock contacts |
| `ContactDataExtractor.kt` | Extract structured data from contacts |
| `ContactParser.kt` | Parse contact information |
| `ContactPhotoLoader.kt` | Load contact photos efficiently |
| `ContactQueryHelper.kt` | Reusable contact query utilities |
| `ContactsContentObserver.kt` | Monitor contacts for changes |
| `VCardGenerator.kt` | Generate vCard format from contact data |
| `VCardModels.kt` | Data models for vCard generation |
| `VCardService.kt` | vCard generation service |

## Architecture

```
Contacts Architecture:

┌─────────────────────────────────────────────────────────────┐
│                  AndroidContactsService                     │
│  - Query by phone number                                    │
│  - Query by email                                           │
│  - Search contacts                                          │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
       ContactParser    ContactPhoto     VCardService
                        Loader
```

## Required Patterns

### Contact Lookup

```kotlin
class AndroidContactsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getContactByPhone(phone: String): ContactInfo? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone)
        )
        return context.contentResolver.query(uri, projection, null, null, null)
            ?.use { cursor -> parseContact(cursor) }
    }
}
```

### Contact Models (VCardModels.kt)

```kotlin
// Top-level classes - import directly
data class ContactData(
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    val phoneNumbers: List<PhoneNumber>,
    val emails: List<Email>,
    val photo: ByteArray?
)

data class FieldOptions(
    val includePhones: Boolean = true,
    val includeEmails: Boolean = true,
    val includeAddresses: Boolean = true,
    val includePhoto: Boolean = true
)
```

### Content Observer

```kotlin
class ContactsContentObserver @Inject constructor() {
    fun startObserving() {
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer
        )
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // Invalidate contact cache
            contactCache.invalidateAll()
        }
    }
}
```

## Best Practices

1. Cache contact lookups (contacts rarely change)
2. Use content observer to invalidate cache
3. Handle missing permissions gracefully
4. Normalize phone numbers before lookup
5. Import `ContactData` and `FieldOptions` directly from `VCardModels.kt`
