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
     * Returns null if contact not found.
     */
    fun getContactId(address: String): Long? {
        if (!hasReadPermission()) return null

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
     * Get the display name for a contact by phone number or email address.
     * Returns null if contact not found or permission denied.
     */
    fun getContactDisplayName(address: String): String? {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return null
        }

        return try {
            // Try phone lookup first
            val phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
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
                    arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
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
