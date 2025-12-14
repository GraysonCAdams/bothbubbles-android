package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for querying contact information from Android ContactsContract.
 * Handles contact ID lookups via phone number or email.
 */
@Singleton
class ContactQueryHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactQueryHelper"

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
     * Get the contact ID for a phone number or email address.
     * Returns null if contact not found or if address is a short code.
     */
    fun getContactId(address: String): Long? {
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
     * Get the nickname for a contact by contact ID.
     * Returns null if no nickname is set.
     */
    fun getContactNickname(contactId: Long): String? {
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
     * Returns null if contact not found or if address is a short code.
     */
    fun getContactDisplayName(address: String): String? {
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
     * Check if a contact is in the device contacts (regardless of starred status).
     */
    fun isContactSaved(address: String): Boolean {
        return getContactId(address) != null
    }
}
