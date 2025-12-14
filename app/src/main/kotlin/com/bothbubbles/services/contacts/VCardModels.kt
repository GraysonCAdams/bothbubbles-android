package com.bothbubbles.services.contacts

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContactData

        if (displayName != other.displayName) return false
        if (givenName != other.givenName) return false
        if (familyName != other.familyName) return false
        if (middleName != other.middleName) return false
        if (prefix != other.prefix) return false
        if (suffix != other.suffix) return false
        if (phoneNumbers != other.phoneNumbers) return false
        if (emails != other.emails) return false
        if (organization != other.organization) return false
        if (title != other.title) return false
        if (photo != null) {
            if (other.photo == null) return false
            if (!photo.contentEquals(other.photo)) return false
        } else if (other.photo != null) return false
        if (note != other.note) return false
        if (addresses != other.addresses) return false

        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + (givenName?.hashCode() ?: 0)
        result = 31 * result + (familyName?.hashCode() ?: 0)
        result = 31 * result + (middleName?.hashCode() ?: 0)
        result = 31 * result + (prefix?.hashCode() ?: 0)
        result = 31 * result + (suffix?.hashCode() ?: 0)
        result = 31 * result + phoneNumbers.hashCode()
        result = 31 * result + emails.hashCode()
        result = 31 * result + (organization?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (photo?.contentHashCode() ?: 0)
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + addresses.hashCode()
        return result
    }
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
