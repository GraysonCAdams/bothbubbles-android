package com.bothbubbles.services.contacts

import android.content.Context
import android.net.Uri
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
) : VCardExporter {
    companion object {
        private const val TAG = "VCardService"
    }

    private val contactDataExtractor = ContactDataExtractor(context)

    /**
     * Creates a vCard file from a contact URI and returns the file URI.
     * Returns null if the contact cannot be read or the file cannot be created.
     */
    override fun createVCardFromContact(contactUri: Uri): Uri? {
        return try {
            val contactData = getContactData(contactUri) ?: return null
            val vCardContent = VCardGenerator.generateVCard(contactData)
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
    override fun createVCardFromContactData(contactData: ContactData, options: FieldOptions): Uri? {
        return try {
            val vCardContent = VCardGenerator.generateVCardWithOptions(contactData, options)
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
    override fun getContactData(contactUri: Uri): ContactData? {
        return contactDataExtractor.getContactData(contactUri)
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
}
