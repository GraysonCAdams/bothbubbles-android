package com.bothbubbles.services.contacts

/**
 * Interface for phone number blocking operations.
 * Allows testing blocking logic without modifying system contacts.
 *
 * This interface defines the contract for blocking/unblocking phone numbers
 * using Android's BlockedNumberContract. Note that blocking requires the app
 * to be the default dialer or SMS app.
 *
 * Implementation: [ContactBlockingService]
 */
interface ContactBlocker {

    /**
     * Check if the app can block numbers.
     * Blocking requires the app to be the default dialer/SMS app or have system privileges.
     *
     * @return true if the app has permission to block numbers
     */
    fun canBlockNumbers(): Boolean

    /**
     * Block a phone number using Android's BlockedNumberContract.
     *
     * Note: Blocking requires the app to be the default dialer or SMS app.
     *
     * @param phoneNumber The phone number to block
     * @return true if successful, false if blocking not available or failed
     */
    fun blockNumber(phoneNumber: String): Boolean

    /**
     * Check if a number is blocked.
     *
     * @param phoneNumber The phone number to check
     * @return true if the number is blocked
     */
    fun isNumberBlocked(phoneNumber: String): Boolean

    /**
     * Unblock a phone number.
     *
     * @param phoneNumber The phone number to unblock
     * @return true if successful
     */
    fun unblockNumber(phoneNumber: String): Boolean

    /**
     * Get all blocked phone numbers.
     * Used by BlockedContactsViewModel to display the list.
     *
     * @return List of blocked phone numbers
     */
    fun getBlockedNumbers(): List<String>
}
