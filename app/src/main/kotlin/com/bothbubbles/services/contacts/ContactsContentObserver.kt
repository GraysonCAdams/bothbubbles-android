package com.bothbubbles.services.contacts

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.bothbubbles.data.local.db.dao.HandleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content observer for monitoring Android contacts database changes.
 *
 * When contacts change (add, edit, delete), this observer triggers
 * a refresh of cached contact info (names, photos) for all handles.
 *
 * This ensures:
 * 1. Newly added contacts are reflected in conversation names
 * 2. Contact name changes are picked up
 * 3. Contact photo changes are reflected
 * 4. Deleted contacts revert to phone number display
 */
@Singleton
class ContactsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handleDao: HandleDao,
    private val androidContactsService: AndroidContactsService
) {
    companion object {
        private const val TAG = "ContactsObserver"
        private const val DEBOUNCE_MS = 2000L // 2 seconds debounce for rapid contact edits
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var debounceJob: Job? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    /**
     * Start observing contacts database for changes
     */
    fun startObserving() {
        if (_isObserving.value) return

        Log.d(TAG, "Starting contacts content observer")

        val handler = Handler(Looper.getMainLooper())

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "Contacts database changed")
                debounceAndRefresh()
            }
        }

        observer?.let {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                it
            )
        }

        _isObserving.value = true
    }

    /**
     * Stop observing contacts database
     */
    fun stopObserving() {
        Log.d(TAG, "Stopping contacts content observer")

        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        debounceJob?.cancel()

        _isObserving.value = false
    }

    /**
     * Debounce contact changes to avoid excessive refreshes during bulk edits
     */
    private fun debounceAndRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            refreshAllCachedContacts()
        }
    }

    /**
     * Refresh cached contact info for all handles in the database.
     * Called when contacts database changes are detected.
     */
    private suspend fun refreshAllCachedContacts() {
        Log.i(TAG, "Refreshing cached contact info for all handles")

        try {
            val handles = handleDao.getAllHandlesOnce()
            var updated = 0

            for (handle in handles) {
                // Look up current contact info from device contacts
                val currentName = androidContactsService.getContactDisplayName(handle.address)
                val currentPhoto = androidContactsService.getContactPhotoUri(handle.address)

                // Only update if there's a change
                if (currentName != handle.cachedDisplayName ||
                    currentPhoto != handle.cachedAvatarPath
                ) {
                    handleDao.updateCachedContactInfo(handle.id, currentName, currentPhoto)
                    updated++

                    // Log significant changes
                    if (handle.cachedDisplayName != null && currentName == null) {
                        Log.d(TAG, "Contact removed for ${handle.address}")
                    } else if (handle.cachedDisplayName == null && currentName != null) {
                        Log.d(TAG, "Contact added for ${handle.address}: $currentName")
                    } else if (handle.cachedDisplayName != currentName) {
                        Log.d(TAG, "Contact name changed for ${handle.address}: ${handle.cachedDisplayName} -> $currentName")
                    }
                }
            }

            Log.i(TAG, "Contact cache refresh complete. Updated $updated of ${handles.size} handles")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing cached contacts", e)
        }
    }

    /**
     * Force a refresh of cached contacts.
     * Can be called manually from settings or after permission grant.
     */
    suspend fun forceRefresh() {
        Log.i(TAG, "Force refreshing cached contacts")
        refreshAllCachedContacts()
    }
}
