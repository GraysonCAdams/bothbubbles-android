package com.bothbubbles.services.contacts

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for blocking and unblocking phone numbers using Android's BlockedNumberContract.
 * Blocking requires the app to be the default dialer or SMS app.
 */
@Singleton
class ContactBlockingService @Inject constructor(
    @ApplicationContext private val context: Context
) : ContactBlocker {

    /**
     * Check if the app can block numbers.
     * Blocking requires the app to be the default dialer/SMS app or have system privileges.
     */
    override fun canBlockNumbers(): Boolean {
        return try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (e: Exception) {
            Timber.w(e, "Error checking if can block numbers")
            false
        }
    }

    /**
     * Block a phone number using Android's BlockedNumberContract.
     * Returns true if successful, false if blocking not available or failed.
     *
     * Note: Blocking requires the app to be the default dialer or SMS app.
     */
    override fun blockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Timber.w("App cannot block numbers - must be default dialer or SMS app")
            return false
        }

        return try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phoneNumber)
            }

            val uri = context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )

            if (uri != null) {
                Timber.d("Successfully blocked number: $phoneNumber")
                true
            } else {
                Timber.w("Failed to block number: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error blocking number: $phoneNumber")
            false
        }
    }

    /**
     * Check if a number is blocked.
     */
    override fun isNumberBlocked(phoneNumber: String): Boolean {
        return try {
            BlockedNumberContract.isBlocked(context, phoneNumber)
        } catch (e: Exception) {
            Timber.w(e, "Error checking if number is blocked: $phoneNumber")
            false
        }
    }

    /**
     * Unblock a phone number.
     * Returns true if successful.
     */
    override fun unblockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Timber.w("App cannot unblock numbers - must be default dialer or SMS app")
            return false
        }

        return try {
            val rowsDeleted = context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                arrayOf(phoneNumber)
            )

            if (rowsDeleted > 0) {
                Timber.d("Successfully unblocked number: $phoneNumber")
                true
            } else {
                Timber.w("Number was not blocked: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error unblocking number: $phoneNumber")
            false
        }
    }

    /**
     * Get all blocked phone numbers.
     */
    override fun getBlockedNumbers(): List<String> {
        val numbers = mutableListOf<String>()

        try {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
                )
                while (cursor.moveToNext()) {
                    val number = cursor.getString(columnIndex)
                    if (number != null) {
                        numbers.add(number)
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Not authorized to access blocked numbers")
        } catch (e: Exception) {
            Timber.e(e, "Error getting blocked numbers")
        }

        return numbers
    }
}
