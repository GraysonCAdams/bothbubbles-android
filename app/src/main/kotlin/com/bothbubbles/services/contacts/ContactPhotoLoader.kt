package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for loading contact photo URIs from Android ContactsContract.
 */
@Singleton
class ContactPhotoLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactPhotoLoader"
    }

    /**
     * Get the photo URI for a contact by phone number or email address.
     * Returns null if contact not found, no photo set, or if address is a short code.
     */
    fun getContactPhotoUri(address: String): String? {
        if (address.isBlank()) return null
        // Skip short codes to avoid false positive matches from fuzzy phone lookup
        if (ContactQueryHelper.isShortCodeOrAlphanumericSender(address)) return null

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
}
