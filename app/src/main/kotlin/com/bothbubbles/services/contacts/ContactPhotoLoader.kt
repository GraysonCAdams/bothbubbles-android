package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import timber.log.Timber
import com.bothbubbles.util.PermissionStateMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for loading contact photo URIs from Android ContactsContract.
 */
@Singleton
class ContactPhotoLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionStateMonitor: PermissionStateMonitor
) {
    /**
     * Get the photo URI for a contact by phone number or email address.
     * Returns null if contact not found, no photo set, if address is a short code,
     * or if contacts permission is not granted.
     */
    fun getContactPhotoUri(address: String): String? {
        if (address.isBlank()) return null
        // Skip short codes to avoid false positive matches from fuzzy phone lookup
        if (ContactQueryHelper.isShortCodeOrAlphanumericSender(address)) return null

        // Guard: Return null if contacts permission was revoked
        if (!permissionStateMonitor.hasContactsPermission()) {
            Timber.d("Contacts permission not granted - cannot load photo for $address")
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
        } catch (e: SecurityException) {
            // Permission was revoked after our check - return gracefully
            Timber.w("SecurityException getting photo URI for $address - permission may have been revoked")
            null
        } catch (e: Exception) {
            Timber.w(e, "Error getting photo URI for $address")
            null
        }
    }
}
