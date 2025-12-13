package com.bothbubbles.ui.chat.details

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Launch the add contact screen with pre-filled info
 */
fun launchAddContact(context: Context, address: String, name: String) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE

        // Check if address looks like a phone number or email
        if (address.contains("@")) {
            putExtra(ContactsContract.Intents.Insert.EMAIL, address)
        } else {
            putExtra(ContactsContract.Intents.Insert.PHONE, address)
        }

        // Only set name if it's different from the address (i.e., not just a number)
        if (name != address) {
            putExtra(ContactsContract.Intents.Insert.NAME, name)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle case where no contacts app is available
    }
}

/**
 * View an existing contact by looking up their phone number or email
 */
fun viewContact(context: Context, address: String) {
    try {
        // Look up contact by phone number or email
        val contactUri = if (address.contains("@")) {
            // Email lookup
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        } else {
            // Phone number lookup
            Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
        }

        val projection = arrayOf(ContactsContract.Contacts._ID)
        val cursor = context.contentResolver.query(contactUri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val contactViewUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactId.toString()
                )
                val intent = Intent(Intent.ACTION_VIEW, contactViewUri)
                context.startActivity(intent)
            }
        }
    } catch (e: Exception) {
        // Handle case where contact lookup fails or no contacts app is available
    }
}
