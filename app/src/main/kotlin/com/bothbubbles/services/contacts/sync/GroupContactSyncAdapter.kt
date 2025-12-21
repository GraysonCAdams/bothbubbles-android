package com.bothbubbles.services.contacts.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import timber.log.Timber

/**
 * Sync adapter for synchronizing group chats to the system contacts.
 *
 * This adapter is called by Android's sync framework, but we primarily
 * trigger syncs manually via [GroupContactSyncManager].
 */
class GroupContactSyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?
    ) {
        Timber.d("GroupContactSyncAdapter.onPerformSync called")
        // The actual sync is performed by GroupContactSyncManager
        // This is just the framework callback
        try {
            GroupContactSyncManager.performSync(context)
        } catch (e: Exception) {
            Timber.e(e, "Error during group contact sync")
            syncResult?.stats?.numIoExceptions = 1
        }
    }
}
