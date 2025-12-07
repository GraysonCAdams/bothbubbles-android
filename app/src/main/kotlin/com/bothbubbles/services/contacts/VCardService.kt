package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for generating vCard (VCF) files from Android contacts.
 * Creates vCard 3.0 format for cross-platform compatibility.
 */
@Singleton
class VCardService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VCardService"
        private const val VCARD_VERSION = "3.0"
    }

    /**
     * Contact data extracted from Android contacts database
     */
    data class ContactData(
        val displayName: String,
        val givenName: String?,
        val familyName: String?,
        val middleName: String?,
        val prefix: String?,
        val suffix: String?,
        val phoneNumbers: List<PhoneEntry>,
        val emails: List<EmailEntry>,
        val organization: String?,
        val title: String?,
        val photo: ByteArray?,
        val note: String?,
        val addresses: List<AddressEntry>
    ) {
        data class PhoneEntry(val number: String, val type: String)
        data class EmailEntry(val address: String, val type: String)
        data class AddressEntry(
            val street: String?,
            val city: String?,
            val region: String?,
            val postcode: String?,
            val country: String?,
            val type: String
        )
    }

    /**
     * Options for which fields to include in a vCard
     */
    data class FieldOptions(
        val includePhones: Boolean = true,
        val includeEmails: Boolean = true,
        val includeOrganization: Boolean = true,
        val includeAddresses: Boolean = true,
        val includeNote: Boolean = true,
        val includePhoto: Boolean = true
    )

    /**
     * Creates a vCard file from a contact URI and returns the file URI.
     * Returns null if the contact cannot be read or the file cannot be created.
     */
    fun createVCardFromContact(contactUri: Uri): Uri? {
        return try {
            val contactData = getContactData(contactUri) ?: return null
            val vCardContent = generateVCard(contactData)
            saveVCardToFile(contactData.displayName, vCardContent)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating vCard from contact", e)
            null
        }
    }

    /**
     * Creates a vCard file from ContactData with field options.
     * Returns null if the file cannot be created.
     */
    fun createVCardFromContactData(contactData: ContactData, options: FieldOptions): Uri? {
        return try {
            val vCardContent = generateVCardWithOptions(contactData, options)
            saveVCardToFile(contactData.displayName, vCardContent)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating vCard from contact data", e)
            null
        }
    }

    /**
     * Extracts full contact data from a contact URI.
     * This can be used to preview contact info before generating vCard.
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

        // Get email addresses
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

        // Get organization
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

        // Get addresses
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

        // Get note
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

        // Get photo
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

    /**
     * Generates vCard 3.0 format string from contact data
     */
    private fun generateVCard(contact: ContactData): String {
        val sb = StringBuilder()

        sb.appendLine("BEGIN:VCARD")
        sb.appendLine("VERSION:$VCARD_VERSION")

        // Full name (required)
        sb.appendLine("FN:${escapeVCardValue(contact.displayName)}")

        // Structured name: Family;Given;Middle;Prefix;Suffix
        val n = listOf(
            contact.familyName ?: "",
            contact.givenName ?: "",
            contact.middleName ?: "",
            contact.prefix ?: "",
            contact.suffix ?: ""
        ).joinToString(";") { escapeVCardValue(it) }
        sb.appendLine("N:$n")

        // Phone numbers
        for (phone in contact.phoneNumbers) {
            sb.appendLine("TEL;TYPE=${phone.type}:${escapeVCardValue(phone.number)}")
        }

        // Email addresses
        for (email in contact.emails) {
            sb.appendLine("EMAIL;TYPE=${email.type}:${escapeVCardValue(email.address)}")
        }

        // Organization
        if (!contact.organization.isNullOrBlank()) {
            sb.appendLine("ORG:${escapeVCardValue(contact.organization)}")
        }

        // Title
        if (!contact.title.isNullOrBlank()) {
            sb.appendLine("TITLE:${escapeVCardValue(contact.title)}")
        }

        // Addresses
        for (addr in contact.addresses) {
            // ADR: PO Box;Extended;Street;City;Region;Postcode;Country
            val adr = listOf(
                "", // PO Box
                "", // Extended address
                addr.street ?: "",
                addr.city ?: "",
                addr.region ?: "",
                addr.postcode ?: "",
                addr.country ?: ""
            ).joinToString(";") { escapeVCardValue(it) }
            sb.appendLine("ADR;TYPE=${addr.type}:$adr")
        }

        // Note
        if (!contact.note.isNullOrBlank()) {
            sb.appendLine("NOTE:${escapeVCardValue(contact.note)}")
        }

        // Photo (base64 encoded JPEG)
        contact.photo?.let { photoBytes ->
            val base64Photo = Base64.encodeToString(photoBytes, Base64.NO_WRAP)
            // Split into 75 character lines for vCard spec compliance
            val photoLines = base64Photo.chunked(75)
            sb.append("PHOTO;ENCODING=b;TYPE=JPEG:")
            photoLines.forEachIndexed { index, line ->
                if (index == 0) {
                    sb.appendLine(line)
                } else {
                    sb.appendLine(" $line") // Continuation lines start with space
                }
            }
        }

        sb.appendLine("END:VCARD")

        return sb.toString()
    }

    /**
     * Generates vCard 3.0 format string from contact data with field options
     */
    private fun generateVCardWithOptions(contact: ContactData, options: FieldOptions): String {
        val sb = StringBuilder()

        sb.appendLine("BEGIN:VCARD")
        sb.appendLine("VERSION:$VCARD_VERSION")

        // Full name (required)
        sb.appendLine("FN:${escapeVCardValue(contact.displayName)}")

        // Structured name: Family;Given;Middle;Prefix;Suffix
        val n = listOf(
            contact.familyName ?: "",
            contact.givenName ?: "",
            contact.middleName ?: "",
            contact.prefix ?: "",
            contact.suffix ?: ""
        ).joinToString(";") { escapeVCardValue(it) }
        sb.appendLine("N:$n")

        // Phone numbers
        if (options.includePhones) {
            for (phone in contact.phoneNumbers) {
                sb.appendLine("TEL;TYPE=${phone.type}:${escapeVCardValue(phone.number)}")
            }
        }

        // Email addresses
        if (options.includeEmails) {
            for (email in contact.emails) {
                sb.appendLine("EMAIL;TYPE=${email.type}:${escapeVCardValue(email.address)}")
            }
        }

        // Organization
        if (options.includeOrganization) {
            if (!contact.organization.isNullOrBlank()) {
                sb.appendLine("ORG:${escapeVCardValue(contact.organization)}")
            }
            if (!contact.title.isNullOrBlank()) {
                sb.appendLine("TITLE:${escapeVCardValue(contact.title)}")
            }
        }

        // Addresses
        if (options.includeAddresses) {
            for (addr in contact.addresses) {
                val adr = listOf(
                    "", // PO Box
                    "", // Extended address
                    addr.street ?: "",
                    addr.city ?: "",
                    addr.region ?: "",
                    addr.postcode ?: "",
                    addr.country ?: ""
                ).joinToString(";") { escapeVCardValue(it) }
                sb.appendLine("ADR;TYPE=${addr.type}:$adr")
            }
        }

        // Note
        if (options.includeNote && !contact.note.isNullOrBlank()) {
            sb.appendLine("NOTE:${escapeVCardValue(contact.note)}")
        }

        // Photo (base64 encoded JPEG)
        if (options.includePhoto) {
            contact.photo?.let { photoBytes ->
                val base64Photo = Base64.encodeToString(photoBytes, Base64.NO_WRAP)
                val photoLines = base64Photo.chunked(75)
                sb.append("PHOTO;ENCODING=b;TYPE=JPEG:")
                photoLines.forEachIndexed { index, line ->
                    if (index == 0) {
                        sb.appendLine(line)
                    } else {
                        sb.appendLine(" $line")
                    }
                }
            }
        }

        sb.appendLine("END:VCARD")

        return sb.toString()
    }

    /**
     * Saves vCard content to a temporary file and returns the file URI
     */
    private fun saveVCardToFile(displayName: String, vCardContent: String): Uri? {
        return try {
            // Create temp directory for vCards
            val vcardDir = File(context.cacheDir, "vcards")
            if (!vcardDir.exists()) {
                vcardDir.mkdirs()
            }

            // Create file with sanitized name
            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val fileName = "${sanitizedName}_${System.currentTimeMillis()}.vcf"
            val vcardFile = File(vcardDir, fileName)

            vcardFile.writeText(vCardContent, Charsets.UTF_8)

            // Return content URI via FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                vcardFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving vCard to file", e)
            null
        }
    }

    /**
     * Gets contact ID from a contact URI
     */
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
            Log.e(TAG, "Error getting contact ID", e)
            null
        }
    }

    /**
     * Converts Android phone type to vCard type string
     */
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

    /**
     * Converts Android email type to vCard type string
     */
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

    /**
     * Converts Android address type to vCard type string
     */
    private fun getAddressTypeLabel(type: Int, customLabel: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER -> "OTHER"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> customLabel?.uppercase() ?: "OTHER"
            else -> "OTHER"
        }
    }

    /**
     * Escapes special characters for vCard format
     */
    private fun escapeVCardValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }
}
