package com.bothbubbles.services.life360

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Life360 authentication tokens using EncryptedSharedPreferences.
 *
 * Tokens are stored separately from regular app preferences for security.
 * Uses AES256 encryption for both keys and values.
 */
@Singleton
class Life360TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * The access token for Life360 API calls.
     */
    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    /**
     * Token type (usually "Bearer").
     */
    var tokenType: String
        get() = prefs.getString(KEY_TOKEN_TYPE, DEFAULT_TOKEN_TYPE) ?: DEFAULT_TOKEN_TYPE
        set(value) = prefs.edit { putString(KEY_TOKEN_TYPE, value) }

    /**
     * Token expiration timestamp (epoch millis).
     */
    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_EXPIRES_AT, value) }

    /**
     * Whether a valid token is stored.
     */
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrEmpty()

    /**
     * Whether the token has expired.
     */
    val isTokenExpired: Boolean
        get() {
            val expiresAt = tokenExpiresAt
            return expiresAt > 0 && System.currentTimeMillis() >= expiresAt
        }

    /**
     * Clear all stored tokens (logout).
     */
    fun clear() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_TOKEN_TYPE)
            remove(KEY_EXPIRES_AT)
        }
    }

    /**
     * Store tokens from authentication response.
     *
     * @param accessToken The access token
     * @param tokenType Token type (usually "Bearer")
     * @param expiresInSeconds Optional expiry time in seconds from now
     */
    fun storeTokens(
        accessToken: String,
        tokenType: String = DEFAULT_TOKEN_TYPE,
        expiresInSeconds: Long? = null
    ) {
        this.accessToken = accessToken
        this.tokenType = tokenType
        this.tokenExpiresAt = expiresInSeconds?.let {
            System.currentTimeMillis() + (it * 1000)
        } ?: 0L
    }

    /**
     * Build the Authorization header value.
     *
     * @return Authorization header value in format "{tokenType} {accessToken}", or null if not authenticated
     */
    fun buildAuthHeader(): String? {
        val token = accessToken ?: return null
        return "$tokenType $token"
    }

    companion object {
        private const val PREFS_NAME = "life360_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val DEFAULT_TOKEN_TYPE = "Bearer"
    }
}
