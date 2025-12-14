package com.bothbubbles.services.contacts

import android.util.Base64

/**
 * Generates vCard 3.0 format strings from contact data.
 * Separated from VCardService for improved testability and single responsibility.
 */
internal object VCardGenerator {

    private const val VCARD_VERSION = "3.0"

    /**
     * Generates vCard 3.0 format string from contact data
     */
    fun generateVCard(contact: ContactData): String {
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
            appendPhoto(sb, photoBytes)
        }

        sb.appendLine("END:VCARD")

        return sb.toString()
    }

    /**
     * Generates vCard 3.0 format string from contact data with field options
     */
    fun generateVCardWithOptions(contact: ContactData, options: FieldOptions): String {
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
                appendPhoto(sb, photoBytes)
            }
        }

        sb.appendLine("END:VCARD")

        return sb.toString()
    }

    private fun appendPhoto(sb: StringBuilder, photoBytes: ByteArray) {
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
