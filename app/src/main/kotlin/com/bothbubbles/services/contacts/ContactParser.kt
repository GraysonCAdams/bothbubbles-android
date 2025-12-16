package com.bothbubbles.services.contacts

import android.content.Context
import android.provider.ContactsContract
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
 * Helper class for parsing contact data from Android ContactsContract.
 * Handles batch queries and cursor parsing for contacts, phone numbers, and emails.
 */
@Singleton
class ContactParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Get all contacts from the phone with valid display names.
     * Returns contacts with at least one phone number or email address.
     * Runs on IO dispatcher. Results are sorted by display name.
     */
    suspend fun getAllContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
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
            parsePhoneNumbers(contactsMap)

            // Batch query emails for all contacts
            parseEmails(contactsMap)

        } catch (e: Exception) {
            Timber.e(e, "Error fetching contacts")
            return@withContext emptyList()
        }

        // Return only contacts that have at least one phone number or email
        contactsMap.values
            .filter { it.phoneNumbers.isNotEmpty() || it.emails.isNotEmpty() }
            .sortedBy { it.displayName.uppercase() }
    }

    /**
     * Get all phone numbers and emails that belong to starred (favorite) contacts.
     * Returns a set of addresses for quick lookup.
     */
    suspend fun getAllStarredAddresses(): Set<String> = withContext(Dispatchers.IO) {
        val starredAddresses = mutableSetOf<String>()

        try {
            // First get all starred contact IDs
            val starredContactIds = getStarredContactIds()

            if (starredContactIds.isEmpty()) {
                return@withContext emptySet()
            }

            // Get phone numbers for starred contacts
            parseStarredPhoneNumbers(starredContactIds, starredAddresses)

            // Get emails for starred contacts
            parseStarredEmails(starredContactIds, starredAddresses)

        } catch (e: Exception) {
            Timber.e(e, "Error fetching starred addresses")
        }

        starredAddresses
    }

    /**
     * Get all starred contact IDs from the database.
     */
    private fun getStarredContactIds(): Set<Long> {
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
        return starredContactIds
    }

    /**
     * Parse phone numbers for a map of contacts using batch query.
     */
    private fun parsePhoneNumbers(contactsMap: MutableMap<Long, PhoneContact>) {
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
    }

    /**
     * Parse emails for a map of contacts using batch query.
     */
    private fun parseEmails(contactsMap: MutableMap<Long, PhoneContact>) {
        val contactIds = contactsMap.keys.joinToString(",")
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
    }

    /**
     * Parse phone numbers for starred contacts and add to the addresses set.
     */
    private fun parseStarredPhoneNumbers(starredContactIds: Set<Long>, starredAddresses: MutableSet<String>) {
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
    }

    /**
     * Parse emails for starred contacts and add to the addresses set.
     */
    private fun parseStarredEmails(starredContactIds: Set<Long>, starredAddresses: MutableSet<String>) {
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
    }
}
