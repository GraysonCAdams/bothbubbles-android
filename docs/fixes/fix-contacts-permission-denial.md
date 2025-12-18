# Fix Plan: Contacts Permission Denial

**Error:** Permission denial accessing ContactsProvider
**Severity:** High
**Log:** `Permission Denial: opening provider ContactsProvider2 ... requires android.permission.READ_CONTACTS`

---

## Problem

The app attempts to access the Contacts provider without having the `READ_CONTACTS` permission granted.

## Root Cause

1. Service restarts after permission revocation
2. Service immediately tries to access contacts without checking permission
3. No permission check before ContentResolver query

## Implementation Plan

### Step 1: Identify Contact Access Points

Search for contact access patterns:

```bash
# Files that access contacts
grep -r "ContactsContract" --include="*.kt"
grep -r "READ_CONTACTS" --include="*.kt"
grep -r "WRITE_CONTACTS" --include="*.kt"
grep -r "contacts.ContentProvider" --include="*.kt"
```

Likely locations:
- `services/eta/NavigationListenerService.kt`
- `services/contacts/ContactsService.kt`
- `data/repository/ContactRepository.kt`

### Step 2: Create Contacts Permission Helper

```kotlin
// util/permissions/ContactsPermissionHelper.kt
@Singleton
class ContactsPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    inline fun <T> withContactsPermission(
        onPermissionDenied: () -> T,
        onPermissionGranted: () -> T
    ): T {
        return if (hasReadPermission()) {
            try {
                onPermissionGranted()
            } catch (e: SecurityException) {
                // Permission revoked between check and use
                onPermissionDenied()
            }
        } else {
            onPermissionDenied()
        }
    }
}
```

### Step 3: Update Contact Repository

```kotlin
// data/repository/ContactRepository.kt
class ContactRepository @Inject constructor(
    private val contentResolver: ContentResolver,
    private val permissionHelper: ContactsPermissionHelper
) {
    fun getContacts(): Flow<List<Contact>> = flow {
        permissionHelper.withContactsPermission(
            onPermissionDenied = {
                emit(emptyList())
            },
            onPermissionGranted = {
                val contacts = queryContacts()
                emit(contacts)
            }
        )
    }

    fun getContactByPhone(phone: String): Contact? {
        return permissionHelper.withContactsPermission(
            onPermissionDenied = { null },
            onPermissionGranted = { queryContactByPhone(phone) }
        )
    }

    private fun queryContacts(): List<Contact> {
        // Existing query logic
    }

    private fun queryContactByPhone(phone: String): Contact? {
        // Existing query logic
    }
}
```

### Step 4: Update NavigationListenerService

```kotlin
class NavigationListenerService : NotificationListenerService() {

    @Inject
    lateinit var permissionHelper: ContactsPermissionHelper

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Only access contacts if we have permission
        val contactName = if (permissionHelper.hasReadPermission()) {
            try {
                lookupContactName(sbn)
            } catch (e: SecurityException) {
                null
            }
        } else {
            null
        }

        // Continue processing with or without contact info
        processNotification(sbn, contactName)
    }
}
```

### Step 5: Add UI Feedback for Missing Permission

When contacts permission is needed but missing:

```kotlin
// In ViewModel or relevant UI state
sealed class ContactsState {
    object Loading : ContactsState()
    data class Loaded(val contacts: List<Contact>) : ContactsState()
    object PermissionRequired : ContactsState()
    object Empty : ContactsState()
}

// In ViewModel
fun loadContacts() {
    viewModelScope.launch {
        _state.value = if (permissionHelper.hasReadPermission()) {
            ContactsState.Loading
        } else {
            ContactsState.PermissionRequired
        }

        contactRepository.getContacts()
            .collect { contacts ->
                _state.value = when {
                    !permissionHelper.hasReadPermission() -> ContactsState.PermissionRequired
                    contacts.isEmpty() -> ContactsState.Empty
                    else -> ContactsState.Loaded(contacts)
                }
            }
    }
}
```

### Step 6: Graceful Degradation

Ensure features work without contacts:

```kotlin
// Example: Message display without contact lookup
fun getDisplayName(address: String): String {
    return permissionHelper.withContactsPermission(
        onPermissionDenied = { formatPhoneNumber(address) },
        onPermissionGranted = {
            contactRepository.getContactByPhone(address)?.name
                ?: formatPhoneNumber(address)
        }
    )
}
```

## Testing

1. Fresh install - deny contacts permission
2. Verify app works with phone numbers only
3. Grant contacts permission
4. Verify contact names appear
5. Revoke contacts permission while app running
6. Verify no crash, graceful fallback to phone numbers

## Success Criteria

- [ ] No `SecurityException` or permission denial logs
- [ ] App works without contacts permission
- [ ] Contact names show when permission granted
- [ ] Graceful fallback when permission revoked
- [ ] UI indicates when contacts permission needed
