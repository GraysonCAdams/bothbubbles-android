package com.bothbubbles.services.contacts

import android.net.Uri

/**
 * Interface for vCard (VCF) export operations.
 * Decouples contact export logic from Android Context for testability.
 *
 * This interface defines the contract for generating vCard files from contacts.
 * Used by ChatSendDelegate and other components that need to share contacts.
 *
 * Implementation: [VCardService]
 */
interface VCardExporter {

    /**
     * Creates a vCard file from a contact URI and returns the file URI.
     *
     * @param contactUri URI of the contact (from ContactsContract)
     * @return File URI for the generated vCard, or null if creation failed
     */
    fun createVCardFromContact(contactUri: Uri): Uri?

    /**
     * Creates a vCard file from ContactData with field options.
     * Allows selective export of contact fields.
     *
     * @param contactData Structured contact information
     * @param options Field selection options for the vCard
     * @return File URI for the generated vCard, or null if creation failed
     */
    fun createVCardFromContactData(contactData: ContactData, options: FieldOptions): Uri?

    /**
     * Extracts full contact data from a contact URI.
     * This can be used to preview contact info before generating vCard.
     *
     * @param contactUri URI of the contact (from ContactsContract)
     * @return Contact data, or null if extraction failed
     */
    fun getContactData(contactUri: Uri): ContactData?
}
