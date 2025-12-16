package com.bothbubbles.services.contacts

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing Discord channel IDs stored in device contacts.
 *
 * Uses a custom MIME type to store the Discord DM channel ID associated with a contact,
 * which syncs automatically via Google Contacts across devices.
 *
 * The channel ID is stored in ContactsContract.Data using:
 * - MIMETYPE: vnd.android.cursor.item/vnd.com.bothbubbles.discord_channel
 * - DATA1: The Discord channel ID (17-19 digit snowflake)
 */
@Singleton
class DiscordContactService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactQueryHelper: ContactQueryHelper
) {
    companion object {
        private const val TAG = "DiscordContactService"
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.com.bothbubbles.discord_channel"
        private const val DISCORD_PACKAGE = "com.discord"

        // Discord channel IDs are snowflake IDs: 17-19 digits
        private val CHANNEL_ID_PATTERN = Regex("^\\d{17,19}$")
    }

    /**
     * Get the Discord channel ID for a contact by phone number or email.
     * Returns null if the contact doesn't have a Discord channel ID set.
     */
    fun getDiscordChannelId(address: String): String? {
        if (address.isBlank()) return null

        val contactId = contactQueryHelper.getContactId(address) ?: return null

        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DATA1),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), MIME_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                    if (dataIndex >= 0) {
                        cursor.getString(dataIndex)?.takeIf { it.isNotBlank() }
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting Discord channel ID for $address", e)
            null
        }
    }

    /**
     * Set or update the Discord channel ID for a contact.
     * Creates a new Data row if one doesn't exist, or updates the existing one.
     *
     * @param address The phone number or email of the contact
     * @param channelId The Discord channel ID (17-19 digit snowflake)
     * @return true if successful, false otherwise
     */
    fun setDiscordChannelId(address: String, channelId: String): Boolean {
        if (address.isBlank() || !isValidChannelId(channelId)) {
            Log.w(TAG, "Invalid address or channel ID: address=$address, channelId=$channelId")
            return false
        }

        val contactId = contactQueryHelper.getContactId(address)
        if (contactId == null) {
            Log.w(TAG, "Contact not found for address: $address")
            return false
        }

        return try {
            // Get the raw contact ID (needed for inserting data)
            val rawContactId = getRawContactId(contactId)
            if (rawContactId == null) {
                Log.w(TAG, "Raw contact not found for contact ID: $contactId")
                return false
            }

            // Check if we already have a data row for this contact
            val existingDataId = getExistingDataId(contactId)

            if (existingDataId != null) {
                // Update existing row
                val values = ContentValues().apply {
                    put(ContactsContract.Data.DATA1, channelId)
                }
                val updated = context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(existingDataId.toString())
                )
                Log.d(TAG, "Updated Discord channel ID for contact $contactId: updated=$updated")
                updated > 0
            } else {
                // Insert new row
                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, MIME_TYPE)
                    put(ContactsContract.Data.DATA1, channelId)
                }
                val uri = context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
                Log.d(TAG, "Inserted Discord channel ID for contact $contactId: uri=$uri")
                uri != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Discord channel ID for $address", e)
            false
        }
    }

    /**
     * Clear the Discord channel ID for a contact.
     *
     * @param address The phone number or email of the contact
     * @return true if successful (or no data existed), false on error
     */
    fun clearDiscordChannelId(address: String): Boolean {
        if (address.isBlank()) return true

        val contactId = contactQueryHelper.getContactId(address) ?: return true // No contact = nothing to clear

        return try {
            val deleted = context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), MIME_TYPE)
            )
            Log.d(TAG, "Cleared Discord channel ID for contact $contactId: deleted=$deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Discord channel ID for $address", e)
            false
        }
    }

    /**
     * Check if the Discord app is installed on the device.
     */
    fun isDiscordInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Validate that a string is a valid Discord channel ID (snowflake format).
     * Discord snowflakes are 17-19 digit numbers.
     */
    fun isValidChannelId(id: String): Boolean {
        return CHANNEL_ID_PATTERN.matches(id.trim())
    }

    /**
     * Get the raw contact ID for a contact ID.
     * A contact can have multiple raw contacts (from different accounts),
     * we return the first one which is typically the primary.
     */
    private fun getRawContactId(contactId: Long): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                    if (idIndex >= 0) cursor.getLong(idIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting raw contact ID for contact $contactId", e)
            null
        }
    }

    /**
     * Get the existing Data row ID for our custom MIME type on a contact.
     */
    private fun getExistingDataId(contactId: Long): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), MIME_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.Data._ID)
                    if (idIndex >= 0) cursor.getLong(idIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting existing data ID for contact $contactId", e)
            null
        }
    }
}
