package com.bothbubbles.services.contacts.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service that hosts the BothBubbles authenticator.
 * Required by Android's account system.
 */
class BothBubblesAuthenticatorService : Service() {

    private lateinit var authenticator: BothBubblesAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = BothBubblesAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return authenticator.iBinder
    }
}
