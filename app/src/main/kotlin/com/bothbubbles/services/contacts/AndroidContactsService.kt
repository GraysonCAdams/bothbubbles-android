package com.bothbubbles.services.contacts

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for reading and writing Android contact starred (favorite) status.
 * Uses ContactsContract to query and update the STARRED column.
 *
 * Delegates to helper classes for specific functionality:
 * - ContactQueryHelper: Contact ID lookup and display name resolution
 * - ContactPhotoLoader: Photo URI retrieval
 * - ContactParser: Parsing contact data from cursors
 * - ContactBlockingService: Number blocking functionality
 */
@Singleton
class AndroidContactsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactQueryHelper: ContactQueryHelper,
    private val contactPhotoLoader: ContactPhotoLoader,
    private val contactParser: ContactParser,
    private val contactBlockingService: ContactBlockingService
) {
    companion object {
        private const val TAG = "AndroidContactsService"

        /**
         * Check if an address is a short code or alphanumeric sender ID.
         * These should not be looked up in contacts as Android's fuzzy phone matching
         * can produce false positive matches (e.g., "60484" matching a contact with
         * that pattern in their number, returning "Google" or "Microsoft").
         */
        fun isShortCodeOrAlphanumericSender(address: String): Boolean {
            return ContactQueryHelper.isShortCodeOrAlphanumericSender(address)
        }
    }

    /**
     * Get all contacts from the phone with valid display names.
     * Returns contacts with at least one phone number or email address.
     * Runs on IO dispatcher. Results are sorted by display name.
     */
    suspend fun getAllContacts(): List<PhoneContact> {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return emptyList()
        }
        return contactParser.getAllContacts()
    }

    /**
     * Get all phone numbers and emails that belong to starred (favorite) contacts.
     * Runs on IO dispatcher. Returns a set of addresses for quick lookup.
     */
    suspend fun getAllStarredAddresses(): Set<String> {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return emptySet()
        }
        return contactParser.getAllStarredAddresses()
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
            val contactId = contactQueryHelper.getContactId(address) ?: return false
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
            val contactId = contactQueryHelper.getContactId(address)
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
        return contactQueryHelper.getContactId(address)
    }

    /**
     * Check if a contact is in the device contacts (regardless of starred status).
     */
    fun isContactSaved(address: String): Boolean {
        if (!hasReadPermission()) return false
        return contactQueryHelper.isContactSaved(address)
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
        return contactPhotoLoader.getContactPhotoUri(address)
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
        return contactQueryHelper.getContactDisplayName(address)
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
        return contactBlockingService.canBlockNumbers()
    }

    /**
     * Block a phone number using Android's BlockedNumberContract.
     * Returns true if successful, false if blocking not available or failed.
     *
     * Note: Blocking requires the app to be the default dialer or SMS app.
     */
    fun blockNumber(phoneNumber: String): Boolean {
        return contactBlockingService.blockNumber(phoneNumber)
    }

    /**
     * Check if a number is blocked.
     */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        return contactBlockingService.isNumberBlocked(phoneNumber)
    }

    /**
     * Unblock a phone number.
     * Returns true if successful.
     */
    fun unblockNumber(phoneNumber: String): Boolean {
        return contactBlockingService.unblockNumber(phoneNumber)
    }
}
