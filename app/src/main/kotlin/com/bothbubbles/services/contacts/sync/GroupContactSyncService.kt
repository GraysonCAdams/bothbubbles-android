package com.bothbubbles.services.contacts.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service that hosts the group contact sync adapter.
 * Required by Android's sync framework.
 */
class GroupContactSyncService : Service() {

    companion object {
        private var syncAdapter: GroupContactSyncAdapter? = null
        private val syncAdapterLock = Any()
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(syncAdapterLock) {
            if (syncAdapter == null) {
                syncAdapter = GroupContactSyncAdapter(applicationContext, true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return syncAdapter?.syncAdapterBinder
    }
}
