package com.bothbubbles.services.contacts

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.util.Log
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
) {
    companion object {
        private const val TAG = "ContactBlockingService"
    }

    /**
     * Check if the app can block numbers.
     * Blocking requires the app to be the default dialer/SMS app or have system privileges.
     */
    fun canBlockNumbers(): Boolean {
        return try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if can block numbers", e)
            false
        }
    }

    /**
     * Block a phone number using Android's BlockedNumberContract.
     * Returns true if successful, false if blocking not available or failed.
     *
     * Note: Blocking requires the app to be the default dialer or SMS app.
     */
    fun blockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Log.w(TAG, "App cannot block numbers - must be default dialer or SMS app")
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
                Log.d(TAG, "Successfully blocked number: $phoneNumber")
                true
            } else {
                Log.w(TAG, "Failed to block number: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking number: $phoneNumber", e)
            false
        }
    }

    /**
     * Check if a number is blocked.
     */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        return try {
            BlockedNumberContract.isBlocked(context, phoneNumber)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if number is blocked: $phoneNumber", e)
            false
        }
    }

    /**
     * Unblock a phone number.
     * Returns true if successful.
     */
    fun unblockNumber(phoneNumber: String): Boolean {
        if (!canBlockNumbers()) {
            Log.w(TAG, "App cannot unblock numbers - must be default dialer or SMS app")
            return false
        }

        return try {
            val rowsDeleted = context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                arrayOf(phoneNumber)
            )

            if (rowsDeleted > 0) {
                Log.d(TAG, "Successfully unblocked number: $phoneNumber")
                true
            } else {
                Log.w(TAG, "Number was not blocked: $phoneNumber")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unblocking number: $phoneNumber", e)
            false
        }
    }
}
