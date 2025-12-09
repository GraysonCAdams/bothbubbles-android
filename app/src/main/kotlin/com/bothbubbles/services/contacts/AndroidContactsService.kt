package com.bothbubbles.services.contacts

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for reading and writing Android contact starred (favorite) status.
 * Uses ContactsContract to query and update the STARRED column.
 */
@Singleton
class AndroidContactsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AndroidContactsService"

        // Short codes (5-6 digit numbers) used by businesses for SMS
        private val SHORT_CODE_PATTERN = Regex("""^\d{5,6}$""")

        // Alphanumeric sender IDs (e.g., "GOOGLE", "AMZN", "BANK")
        private val ALPHANUMERIC_SENDER_PATTERN = Regex("""^[A-Za-z]{3,11}$""")

        /**
         * Check if an address is a short code or alphanumeric sender ID.
         * These should not be looked up in contacts as Android's fuzzy phone matching
         * can produce false positive matches (e.g., "60484" matching a contact with
         * that pattern in their number, returning "Google" or "Microsoft").
         */
        fun isShortCodeOrAlphanumericSender(address: String): Boolean {
            val normalized = address.replace(Regex("[^0-9a-zA-Z]"), "")
            return SHORT_CODE_PATTERN.matches(normalized) ||
                   ALPHANUMERIC_SENDER_PATTERN.matches(normalized)
        }
    }

    /**
     * Represents a contact from the Android phone contacts.
     */
    data class PhoneContact(
        val contactId: Long,
        val displayName: String,
        val phoneNumbers: List<String>,
        val emails: List<String>,
        val photoUri: String?,
        val isStarred: Boolean
    )

    /**
     * Get all contacts from the phone with valid display names.
     * Returns contacts with at least one phone number or email address.
     * Runs on IO dispatcher. Results are sorted by display name.
     */
    suspend fun getAllContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return@withContext emptyList()
        }

        val contactsMap = mutableMapOf<Long, PhoneContact>()

        try {
            // Query all contacts with valid display names
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_URI,
                    ContactsContract.Contacts.STARRED,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL AND ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} != ''",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIndex = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                val starredIndex = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                val hasPhoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (cursor.moveToNext()) {
                    val contactId = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } else null
                    if (displayName == null) continue

                    val photoUri = if (photoIndex >= 0) cursor.getString(photoIndex) else null
                    val isStarred = if (starredIndex >= 0) cursor.getInt(starredIndex) == 1 else false
                    val hasPhone = if (hasPhoneIndex >= 0) cursor.getInt(hasPhoneIndex) == 1 else false

                    contactsMap[contactId] = PhoneContact(
                        contactId = contactId,
                        displayName = displayName,
                        phoneNumbers = emptyList(),
                        emails = emptyList(),
                        photoUri = photoUri,
                        isStarred = isStarred
                    )
                }
            }

            if (contactsMap.isEmpty()) {
                return@withContext emptyList()
            }

            // Batch query phone numbers for all contacts
            val contactIds = contactsMap.keys.joinToString(",")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($contactIds)",
                null,
                null
            )?.use { cursor ->
                val contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val contactId = if (contactIdIndex >= 0) cursor.getLong(contactIdIndex) else continue
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex)?.takeIf { it.isNotBlank() } else null
                    if (number == null) continue

                    contactsMap[contactId]?.let { contact ->
                        contactsMap[contactId] = contact.copy(
                            phoneNumbers = contact.phoneNumbers + number
                        )
                    }
                }
            }

            // Batch query emails for all contacts
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($contactIds)",
                null,
                null
            )?.use { cursor ->
                val contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val emailIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)

                while (cursor.moveToNext()) {
                    val contactId = if (contactIdIndex >= 0) cursor.getLong(contactIdIndex) else continue
                    val email = if (emailIndex >= 0) cursor.getString(emailIndex)?.takeIf { it.isNotBlank() } else null
                    if (email == null) continue

                    contactsMap[contactId]?.let { contact ->
                        contactsMap[contactId] = contact.copy(
                            emails = contact.emails + email
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contacts", e)
            return@withContext emptyList()
        }

        // Return only contacts that have at least one phone number or email
        contactsMap.values
            .filter { it.phoneNumbers.isNotEmpty() || it.emails.isNotEmpty() }
            .sortedBy { it.displayName.uppercase() }
    }

    /**
     * Get all phone numbers and emails that belong to starred (favorite) contacts.
     * Runs on IO dispatcher. Returns a set of addresses for quick lookup.
     */
    suspend fun getAllStarredAddresses(): Set<String> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return@withContext emptySet()
        }

        val starredAddresses = mutableSetOf<String>()

        try {
            // First get all starred contact IDs
            val starredContactIds = mutableSetOf<Long>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.STARRED} = 1",
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                while (cursor.moveToNext()) {
                    if (idIndex >= 0) {
                        starredContactIds.add(cursor.getLong(idIndex))
                    }
                }
            }

            if (starredContactIds.isEmpty()) {
                return@withContext emptySet()
            }

            // Get phone numbers for starred contacts
            val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN (${starredContactIds.joinToString(",")})"
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                phoneSelection,
                null,
                null
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    if (numberIndex >= 0) {
                        cursor.getString(numberIndex)?.let { number ->
                            // Store both raw and normalized versions
                            starredAddresses.add(number)
                            starredAddresses.add(number.replace(Regex("[^0-9+]"), ""))
                        }
                    }
                }
            }

            // Get emails for starred contacts
            val emailSelection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN (${starredContactIds.joinToString(",")})"
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                emailSelection,
                null,
                null
            )?.use { cursor ->
                val emailIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    if (emailIndex >= 0) {
                        cursor.getString(emailIndex)?.let { email ->
                            starredAddresses.add(email.lowercase())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching starred addresses", e)
        }

        starredAddresses
    }

    /**
     * Check if a phone number or email is starred (favorite) in Android contacts.
     * Returns false if contact not found or permission denied.
     */
    fun isContactStarred(address: String): Boolean {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return false
        }

        return try {
            val contactId = getContactId(address) ?: return false
            isContactIdStarred(contactId)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking starred status for $address", e)
            false
        }
    }

    /**
     * Set the starred (favorite) status for a contact.
     * Returns true if successful, false otherwise.
     */
    fun setContactStarred(address: String, starred: Boolean): Boolean {
        if (!hasWritePermission()) {
            Log.w(TAG, "WRITE_CONTACTS permission not granted")
            return false
        }

        return try {
            val contactId = getContactId(address)
            if (contactId == null) {
                Log.w(TAG, "Contact not found for address: $address")
                return false
            }

            val contactUri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contactId
            )

            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }

            val rowsUpdated = context.contentResolver.update(
                contactUri,
                values,
                null,
                null
            )

            if (rowsUpdated > 0) {
                Log.d(TAG, "Successfully set starred=$starred for contact $address (id: $contactId)")
                true
            } else {
                Log.w(TAG, "Failed to update starred status for contact $address")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting starred status for $address", e)
            false
        }
    }

    /**
     * Get the contact ID for a phone number or email address.
     * Returns null if contact not found or if address is a short code.
     */
    fun getContactId(address: String): Long? {
        if (!hasReadPermission()) return null
        if (address.isBlank()) return null
        // Skip short codes to avoid false positive matches from fuzzy phone lookup
        if (isShortCodeOrAlphanumericSender(address)) return null

        return try {
            // Try phone lookup first
            val phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID)
                    )
                }
            }

            // Try email lookup if it looks like an email
            if (address.contains("@")) {
                val emailUri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                context.contentResolver.query(
                    emailUri,
                    arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                        )
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting contact ID for $address", e)
            null
        }
    }

    /**
     * Check if a contact is in the device contacts (regardless of starred status).
     */
    fun isContactSaved(address: String): Boolean {
        return getContactId(address) != null
    }

    /**
     * Get the photo URI for a contact by phone number or email address.
     * Returns null if contact not found, no photo set, permission denied, or if address is a short code.
     */
    fun getContactPhotoUri(address: String): String? {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return null
        }
        if (address.isBlank()) return null
        // Skip short codes to avoid false positive matches from fuzzy phone lookup
        if (isShortCodeOrAlphanumericSender(address)) return null

        return try {
            // Try phone lookup first
            val phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val photoIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                    if (photoIndex >= 0) {
                        val photoUri = cursor.getString(photoIndex)
                        if (!photoUri.isNullOrBlank()) {
                            return photoUri
                        }
                    }
                }
            }

            // Try email lookup if it looks like an email
            if (address.contains("@")) {
                val emailUri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                context.contentResolver.query(
                    emailUri,
                    arrayOf(ContactsContract.CommonDataKinds.Email.PHOTO_URI),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val photoIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.PHOTO_URI)
                        if (photoIndex >= 0) {
                            val photoUri = cursor.getString(photoIndex)
                            if (!photoUri.isNullOrBlank()) {
                                return photoUri
                            }
                        }
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting photo URI for $address", e)
            null
        }
    }

    /**
     * Get the nickname for a contact by contact ID.
     * Returns null if no nickname is set.
     */
    private fun getContactNickname(contactId: Long): String? {
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nicknameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Nickname.NAME)
                    if (nicknameIndex >= 0) {
                        return cursor.getString(nicknameIndex)?.takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting nickname for contact $contactId", e)
            null
        }
    }

    /**
     * Get the display name for a contact by phone number or email address.
     * Priority: nickname > display name
     * Returns null if contact not found, permission denied, or if address is a short code.
     */
    fun getContactDisplayName(address: String): String? {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return null
        }
        if (address.isBlank()) return null
        // Skip short codes to avoid false positive matches from fuzzy phone lookup
        if (isShortCodeOrAlphanumericSender(address)) return null

        return try {
            // Try phone lookup first
            val phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)

                    // Get contact ID to check for nickname
                    val contactId = if (idIndex >= 0) cursor.getLong(idIndex) else -1L

                    // Check for nickname first (user-set name takes priority)
                    if (contactId >= 0) {
                        val nickname = getContactNickname(contactId)
                        if (nickname != null) {
                            return nickname
                        }
                    }

                    // Fall back to display name
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                    }
                }
            }

            // Try email lookup if it looks like an email
            if (address.contains("@")) {
                val emailUri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                context.contentResolver.query(
                    emailUri,
                    arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)

                        // Get contact ID to check for nickname
                        val contactId = if (idIndex >= 0) cursor.getLong(idIndex) else -1L

                        // Check for nickname first (user-set name takes priority)
                        if (contactId >= 0) {
                            val nickname = getContactNickname(contactId)
                            if (nickname != null) {
                                return nickname
                            }
                        }

                        // Fall back to display name
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting display name for $address", e)
            null
        }
    }

    /**
     * Check if READ_CONTACTS permission is granted.
     */
    fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if WRITE_CONTACTS permission is granted.
     */
    fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if a contact ID is starred.
     */
    private fun isContactIdStarred(contactId: Long): Boolean {
        val contactUri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI,
            contactId
        )

        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.STARRED),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val starredIndex = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                if (starredIndex >= 0) {
                    return cursor.getInt(starredIndex) == 1
                }
            }
        }

        return false
    }

    /**
     * Check if the app can block numbers.
     * Blocking requires the app to be the default dialer/SMS app or have system privileges.
     */
    fun canBlockNumbers(): Boolean {
        return try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if can block numbers", e)
            false
        }
    }

    /**
     * Block a phone number using Android's BlockedNumberContract.
     * Returns true if successful, false if blocking not available or failed.
     *
     * Note: Blocking requires the app to be the default dialer or SMS app.
     */
    fun blockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Log.w(TAG, "App cannot block numbers - must be default dialer or SMS app")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phoneNumber)
            }

            val uri = context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )

            if (uri != null) {
                Log.d(TAG, "Successfully blocked number: $phoneNumber")
                true
            } else {
                Log.w(TAG, "Failed to block number: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking number: $phoneNumber", e)
            false
        }
    }

    /**
     * Check if a number is blocked.
     */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        return try {
            BlockedNumberContract.isBlocked(context, phoneNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if number is blocked: $phoneNumber", e)
            false
        }
    }

    /**
     * Unblock a phone number.
     * Returns true if successful.
     */
    fun unblockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Log.w(TAG, "App cannot unblock numbers - must be default dialer or SMS app")
            return false
        }

        return try {
            val rowsDeleted = context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                arrayOf(phoneNumber)
            )

            if (rowsDeleted > 0) {
                Log.d(TAG, "Successfully unblocked number: $phoneNumber")
                true
            } else {
                Log.w(TAG, "Number was not blocked: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unblocking number: $phoneNumber", e)
            false
        }
    }
}
