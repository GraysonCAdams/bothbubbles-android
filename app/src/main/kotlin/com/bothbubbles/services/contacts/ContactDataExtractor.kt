package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Extracts contact data from Android ContactsContract.
 * Separated from VCardService for improved testability and code organization.
 */
internal class ContactDataExtractor(private val context: Context) {

    /**
     * Extracts full contact data from a contact URI.
     */
    fun getContactData(contactUri: Uri): ContactData? {
        // Get contact ID
        val contactId = getContactIdFromUri(contactUri) ?: return null

        // Query basic contact info
        var displayName = ""
        var givenName: String? = null
        var familyName: String? = null
        var middleName: String? = null
        var prefix: String? = null
        var suffix: String? = null

        // Get display name from contact
        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0) ?: ""
            }
        }

        // Get structured name
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                ContactsContract.CommonDataKinds.StructuredName.PREFIX,
                ContactsContract.CommonDataKinds.StructuredName.SUFFIX
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                givenName = cursor.getString(0)
                familyName = cursor.getString(1)
                middleName = cursor.getString(2)
                prefix = cursor.getString(3)
                suffix = cursor.getString(4)
            }
        }

        // Get phone numbers
        val phoneNumbers = getPhoneNumbers(contactId)

        // Get email addresses
        val emails = getEmailAddresses(contactId)

        // Get organization
        val (organization, title) = getOrganizationData(contactId)

        // Get addresses
        val addresses = getAddresses(contactId)

        // Get note
        val note = getNote(contactId)

        // Get photo
        val photo = getPhoto(contactId)

        return ContactData(
            displayName = displayName,
            givenName = givenName,
            familyName = familyName,
            middleName = middleName,
            prefix = prefix,
            suffix = suffix,
            phoneNumbers = phoneNumbers,
            emails = emails,
            organization = organization,
            title = title,
            photo = photo,
            note = note,
            addresses = addresses
        )
    }

    private fun getContactIdFromUri(contactUri: Uri): Long? {
        return try {
            context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getPhoneNumbers(contactId: Long): List<ContactData.PhoneEntry> {
        val phoneNumbers = mutableListOf<ContactData.PhoneEntry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0) ?: continue
                val typeInt = cursor.getInt(1)
                val label = cursor.getString(2)
                val type = getPhoneTypeLabel(typeInt, label)
                phoneNumbers.add(ContactData.PhoneEntry(number, type))
            }
        }
        return phoneNumbers
    }

    private fun getEmailAddresses(contactId: Long): List<ContactData.EmailEntry> {
        val emails = mutableListOf<ContactData.EmailEntry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL
            ),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0) ?: continue
                val typeInt = cursor.getInt(1)
                val label = cursor.getString(2)
                val type = getEmailTypeLabel(typeInt, label)
                emails.add(ContactData.EmailEntry(address, type))
            }
        }
        return emails
    }

    private fun getOrganizationData(contactId: Long): Pair<String?, String?> {
        var organization: String? = null
        var title: String? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.TITLE
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                organization = cursor.getString(0)
                title = cursor.getString(1)
            }
        }
        return Pair(organization, title)
    }

    private fun getAddresses(contactId: Long): List<ContactData.AddressEntry> {
        val addresses = mutableListOf<ContactData.AddressEntry>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.LABEL
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val street = cursor.getString(0)
                val city = cursor.getString(1)
                val region = cursor.getString(2)
                val postcode = cursor.getString(3)
                val country = cursor.getString(4)
                val typeInt = cursor.getInt(5)
                val label = cursor.getString(6)
                val type = getAddressTypeLabel(typeInt, label)

                if (street != null || city != null || region != null || postcode != null || country != null) {
                    addresses.add(ContactData.AddressEntry(street, city, region, postcode, country, type))
                }
            }
        }
        return addresses
    }

    private fun getNote(contactId: Long): String? {
        var note: String? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                note = cursor.getString(0)
            }
        }
        return note
    }

    private fun getPhoto(contactId: Long): ByteArray? {
        var photo: ByteArray? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                photo = cursor.getBlob(0)
            }
        }
        return photo
    }

    private fun getPhoneTypeLabel(type: Int, customLabel: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "FAX,WORK"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "FAX,HOME"
            ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "PAGER"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "PREF"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel?.uppercase() ?: "OTHER"
            else -> "OTHER"
        }
    }

    private fun getEmailTypeLabel(type: Int, customLabel: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Email.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "CELL"
            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> customLabel?.uppercase() ?: "OTHER"
            else -> "OTHER"
        }
    }

    private fun getAddressTypeLabel(type: Int, customLabel: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> customLabel?.uppercase() ?: "OTHER"
            else -> "OTHER"
        }
    }
}
