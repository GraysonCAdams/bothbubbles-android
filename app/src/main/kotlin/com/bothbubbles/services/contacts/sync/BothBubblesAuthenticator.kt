package com.bothbubbles.services.contacts.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle

/**
 * Stub authenticator for BothBubbles account type.
 *
 * This authenticator is required by Android's account system but doesn't perform
 * actual authentication. It's needed to register contacts under a BothBubbles account
 * so Google Assistant can match group chat names.
 *
 * The account system provides automatic cleanup when the app is uninstalled -
 * all contacts associated with our account type are automatically removed.
 */
class BothBubblesAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    companion object {
        const val ACCOUNT_TYPE = "com.bothbubbles.account"
        const val ACCOUNT_NAME = "BothBubbles"
    }

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle? = null

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle? = null
}
