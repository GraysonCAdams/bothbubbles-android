package com.bothbubbles.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import com.bothbubbles.util.parsing.DetectedDate
import java.util.Calendar

/**
 * Utility functions for clipboard operations and launching intents.
 * Extracted from MessageBubble.kt for reuse across the codebase.
 */

/**
 * Copies text to clipboard and shows a toast.
 */
fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Copied text", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/**
 * Opens SMS app to compose a message to the given phone number.
 */
fun openSmsIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:$phoneNumber")
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No SMS app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the phone dialer with the given phone number.
 */
fun openDialerIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:$phoneNumber")
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No dialer app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the add contact screen with the given phone number pre-filled.
 */
fun openAddContactIntent(context: Context, phoneNumber: String) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens the calendar app to add a new event.
 */
fun openCalendarIntent(
    context: Context,
    detectedDate: DetectedDate,
    messageText: String,
    allDetectedDates: List<DetectedDate> = emptyList()
) {
    val calendar = detectedDate.parsedDate

    // Set default event duration (1 hour if time is specified, all-day otherwise)
    val startMillis = calendar.timeInMillis
    val endMillis = if (detectedDate.hasTime) {
        startMillis + 60 * 60 * 1000 // 1 hour
    } else {
        // For all-day events, end at the same day
        Calendar.getInstance().apply {
            timeInMillis = startMillis
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }

    // Extract event title by removing date parts and prepositions
    val eventTitle = extractEventTitle(messageText, allDetectedDates.ifEmpty { listOf(detectedDate) })

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        // Only set title if we extracted something meaningful
        if (eventTitle.isNotBlank()) {
            putExtra(CalendarContract.Events.TITLE, eventTitle)
        }
        putExtra(CalendarContract.Events.DESCRIPTION, messageText)
        if (!detectedDate.hasTime) {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No calendar app found - could show a toast here
    }
}

/**
 * Extracts event title by removing date parts and common prepositions from the message.
 * Returns empty string if no meaningful title can be extracted.
 */
fun extractEventTitle(messageText: String, detectedDates: List<DetectedDate>): String {
    var result = messageText

    // Remove all detected date substrings from the message
    // Process in reverse order to maintain correct indices
    detectedDates.sortedByDescending { it.startIndex }.forEach { date ->
        result = result.removeRange(date.startIndex, date.endIndex)
    }

    // Remove common prepositions and connecting words that precede/follow dates
    val prepositionsToRemove = listOf(
        "\\bat\\b", "\\bon\\b", "\\bfor\\b", "\\bto\\b", "\\bfrom\\b",
        "\\buntil\\b", "\\bby\\b", "\\bstarting\\b", "\\bbegins\\b",
        "\\bstarts\\b", "\\bscheduled\\b", "\\bset\\b", "\\bplanned\\b",
        "\\bthe\\b", "\\bis\\b", "\\bare\\b",
        // Relative date words that might remain after date removal
        "\\btomorrow\\b", "\\btoday\\b", "\\bnext\\b", "\\bthis\\b",
        "\\bweek\\b", "\\bmonth\\b", "\\byear\\b", "\\bweekend\\b",
        "\\bmonday\\b", "\\btuesday\\b", "\\bwednesday\\b", "\\bthursday\\b",
        "\\bfriday\\b", "\\bsaturday\\b", "\\bsunday\\b"
    )

    for (prep in prepositionsToRemove) {
        result = result.replace(Regex(prep, RegexOption.IGNORE_CASE), " ")
    }

    // Clean up the result
    result = result
        // Remove multiple spaces
        .replace(Regex("\\s+"), " ")
        // Remove leading/trailing punctuation and whitespace
        .trim()
        .trimStart(':', '-', ',', '.', '!', '?', ';')
        .trimEnd(':', '-', ',', '.', '!', '?', ';')
        .trim()

    // If the result is too short or just punctuation/whitespace, return empty
    if (result.length < 2 || result.all { !it.isLetterOrDigit() }) {
        return ""
    }

    // Capitalize first letter if needed
    return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
