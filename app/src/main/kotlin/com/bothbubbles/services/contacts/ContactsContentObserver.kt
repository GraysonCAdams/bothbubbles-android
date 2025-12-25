package com.bothbubbles.services.contacts

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.di.IoDispatcher
import com.bothbubbles.services.avatar.AvatarResolver
import com.bothbubbles.services.notifications.ShortcutRefreshManager
import com.bothbubbles.util.PermissionStateMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val androidContactsService: AndroidContactsService,
    private val permissionStateMonitor: PermissionStateMonitor,
    private val avatarResolver: AvatarResolver,
    private val shortcutRefreshManager: ShortcutRefreshManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ContactsObserver"
        private const val DEBOUNCE_MS = 2000L // 2 seconds debounce for rapid contact edits
    }

    private var observer: ContentObserver? = null
    private var handler: Handler? = null
    private var debounceJob: Job? = null

    private val _isObserving = MutableStateFlow(false)
    val isObserving: StateFlow<Boolean> = _isObserving.asStateFlow()

    /**
     * Start observing contacts database for changes.
     * Will not start if contacts permission is not granted.
     */
    fun startObserving() {
        if (_isObserving.value) return

        // Guard: Don't start observing if we don't have permission
        if (!permissionStateMonitor.hasContactsPermission()) {
            Timber.w("Cannot start contacts observer - permission not granted")
            return
        }

        Timber.d("Starting contacts content observer")

        handler = handler ?: Handler(Looper.getMainLooper())

        observer = object : ContentObserver(handler!!) {
            override fun onChange(selfChange: Boolean) {
                // Guard: Skip refresh if permission was revoked
                if (!permissionStateMonitor.hasContactsPermission()) {
                    Timber.w("Contacts permission revoked - skipping refresh")
                    stopObserving()
                    return
                }
                Timber.d("Contacts database changed")
                debounceAndRefresh()
            }
        }

        try {
            observer?.let {
                context.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    it
                )
            }
            _isObserving.value = true
        } catch (e: SecurityException) {
            Timber.w("SecurityException registering contacts observer - permission may have been revoked")
            observer = null
        }
    }

    /**
     * Stop observing contacts database
     */
    fun stopObserving() {
        Timber.d("Stopping contacts content observer")

        handler?.removeCallbacksAndMessages(null)
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        handler = null
        debounceJob?.cancel()

        _isObserving.value = false
    }

    /**
     * Debounce contact changes to avoid excessive refreshes during bulk edits
     */
    private fun debounceAndRefresh() {
        debounceJob?.cancel()
        debounceJob = applicationScope.launch(ioDispatcher) {
            delay(DEBOUNCE_MS)
            refreshAllCachedContacts()
        }
    }

    /**
     * Refresh cached contact info for all handles in the database.
     * Called when contacts database changes are detected.
     * Skips refresh if contacts permission is not granted.
     */
    private suspend fun refreshAllCachedContacts() {
        // Guard: Skip refresh if permission was revoked
        if (!permissionStateMonitor.hasContactsPermission()) {
            Timber.w("Contacts permission not granted - skipping contact cache refresh")
            return
        }

        Timber.i("Refreshing cached contact info for all handles")

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
                        Timber.d("Contact removed for ${handle.address}")
                    } else if (handle.cachedDisplayName == null && currentName != null) {
                        Timber.d("Contact added for ${handle.address}: $currentName")
                    } else if (handle.cachedDisplayName != currentName) {
                        Timber.d("Contact name changed for ${handle.address}: ${handle.cachedDisplayName} -> $currentName")
                    }
                }
            }

            Timber.i("Contact cache refresh complete. Updated $updated of ${handles.size} handles")

            // If any contacts were updated, invalidate avatar cache and refresh shortcuts
            if (updated > 0) {
                Timber.d("Invalidating avatar cache after $updated contact updates")
                avatarResolver.invalidateAll()

                Timber.d("Refreshing conversation shortcuts with updated contact photos")
                shortcutRefreshManager.refreshAllShortcuts()
            }
        } catch (e: SecurityException) {
            // Permission was revoked during refresh - return gracefully
            Timber.w("SecurityException refreshing contacts cache - permission may have been revoked")
        } catch (e: Exception) {
            Timber.e(e, "Error refreshing cached contacts")
        }
    }

    /**
     * Force a refresh of cached contacts.
     * Can be called manually from settings or after permission grant.
     * Skips refresh if contacts permission is not granted.
     */
    suspend fun forceRefresh() {
        if (!permissionStateMonitor.hasContactsPermission()) {
            Timber.w("Cannot force refresh contacts - permission not granted")
            return
        }
        Timber.i("Force refreshing cached contacts")
        refreshAllCachedContacts()
    }
}
