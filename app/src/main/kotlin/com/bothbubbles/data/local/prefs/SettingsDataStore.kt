package com.bothbubbles.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.bothbubbles.data.ServerCapabilities
import com.bothbubbles.services.sound.SoundTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Main SettingsDataStore that composes all specialized preference classes.
 * This class delegates to feature-specific preference stores for better organization.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // Specialized preference stores
    private val serverPrefs = ServerPreferences(dataStore)
    private val notificationPrefs = NotificationPreferences(dataStore)
    private val smsPrefs = SmsPreferences(dataStore)
    private val uiPrefs = UiPreferences(dataStore)
    private val syncPrefs = SyncPreferences(dataStore)
    private val attachmentPrefs = AttachmentPreferences(dataStore)
    private val featurePrefs = FeaturePreferences(dataStore)

    // ===== Server Connection (delegated to ServerPreferences) =====

    val serverAddress: Flow<String> get() = serverPrefs.serverAddress
    val guidAuthKey: Flow<String> get() = serverPrefs.guidAuthKey
    val customHeaders: Flow<Map<String, String>> get() = serverPrefs.customHeaders
    val isSetupComplete: Flow<Boolean> get() = serverPrefs.isSetupComplete
    val lastSyncTimestamp: Flow<Long> get() = serverPrefs.lastSyncTimestamp
    val serverPassword: Flow<String> get() = serverPrefs.serverPassword
    val lastSyncTime: Flow<Long> get() = serverPrefs.lastSyncTime

    // Server Capabilities
    val serverOsVersion: Flow<String> get() = serverPrefs.serverOsVersion
    val serverVersionStored: Flow<String> get() = serverPrefs.serverVersionStored
    val serverPrivateApiEnabled: Flow<Boolean> get() = serverPrefs.serverPrivateApiEnabled
    val serverHelperConnected: Flow<Boolean> get() = serverPrefs.serverHelperConnected
    val serverCapabilities: Flow<ServerCapabilities> get() = serverPrefs.serverCapabilities

    suspend fun setServerAddress(address: String) = serverPrefs.setServerAddress(address)
    suspend fun setGuidAuthKey(key: String) = serverPrefs.setGuidAuthKey(key)
    suspend fun setCustomHeaders(headers: Map<String, String>) = serverPrefs.setCustomHeaders(headers)
    suspend fun setSetupComplete(complete: Boolean) = serverPrefs.setSetupComplete(complete)
    suspend fun setLastSyncTimestamp(timestamp: Long) = serverPrefs.setLastSyncTimestamp(timestamp)
    suspend fun setLastSyncTime(timestamp: Long) = serverPrefs.setLastSyncTime(timestamp)
    suspend fun setServerCapabilities(
        osVersion: String?,
        serverVersion: String?,
        privateApiEnabled: Boolean,
        helperConnected: Boolean
    ) = serverPrefs.setServerCapabilities(osVersion, serverVersion, privateApiEnabled, helperConnected)

    // ===== Notification Settings (delegated to NotificationPreferences) =====

    val notificationsEnabled: Flow<Boolean> get() = notificationPrefs.notificationsEnabled
    val notifyOnChatList: Flow<Boolean> get() = notificationPrefs.notifyOnChatList
    val bubbleFilterMode: Flow<String> get() = notificationPrefs.bubbleFilterMode
    val notificationProvider: Flow<String> get() = notificationPrefs.notificationProvider
    val fcmToken: Flow<String> get() = notificationPrefs.fcmToken
    val fcmTokenRegistered: Flow<Boolean> get() = notificationPrefs.fcmTokenRegistered
    val firebaseProjectNumber: Flow<String> get() = notificationPrefs.firebaseProjectNumber
    val firebaseProjectId: Flow<String> get() = notificationPrefs.firebaseProjectId
    val firebaseAppId: Flow<String> get() = notificationPrefs.firebaseAppId
    val firebaseApiKey: Flow<String> get() = notificationPrefs.firebaseApiKey
    val firebaseStorageBucket: Flow<String> get() = notificationPrefs.firebaseStorageBucket
    val keepAlive: Flow<Boolean> get() = notificationPrefs.keepAlive

    suspend fun setNotificationsEnabled(enabled: Boolean) = notificationPrefs.setNotificationsEnabled(enabled)
    suspend fun setNotifyOnChatList(enabled: Boolean) = notificationPrefs.setNotifyOnChatList(enabled)
    suspend fun setBubbleFilterMode(mode: String) = notificationPrefs.setBubbleFilterMode(mode)
    suspend fun setNotificationProvider(provider: String) = notificationPrefs.setNotificationProvider(provider)
    suspend fun setFcmToken(token: String) = notificationPrefs.setFcmToken(token)
    suspend fun setFcmTokenRegistered(registered: Boolean) = notificationPrefs.setFcmTokenRegistered(registered)
    suspend fun setFirebaseConfig(
        projectNumber: String,
        projectId: String,
        appId: String,
        apiKey: String,
        storageBucket: String
    ) = notificationPrefs.setFirebaseConfig(projectNumber, projectId, appId, apiKey, storageBucket)
    suspend fun clearFirebaseConfig() = notificationPrefs.clearFirebaseConfig()
    suspend fun setKeepAlive(enabled: Boolean) = notificationPrefs.setKeepAlive(enabled)

    // ===== SMS Settings (delegated to SmsPreferences) =====

    val smsEnabled: Flow<Boolean> get() = smsPrefs.smsEnabled
    val smsOnlyMode: Flow<Boolean> get() = smsPrefs.smsOnlyMode
    val autoRetryAsSms: Flow<Boolean> get() = smsPrefs.autoRetryAsSms
    val preferSmsOverIMessage: Flow<Boolean> get() = smsPrefs.preferSmsOverIMessage
    val selectedSimSlot: Flow<Int> get() = smsPrefs.selectedSimSlot
    val hasCompletedInitialSmsImport: Flow<Boolean> get() = smsPrefs.hasCompletedInitialSmsImport
    val lastSmsResyncVersion: Flow<Int> get() = smsPrefs.lastSmsResyncVersion
    val blockUnknownSenders: Flow<Boolean> get() = smsPrefs.blockUnknownSenders

    suspend fun setSmsEnabled(enabled: Boolean) = smsPrefs.setSmsEnabled(enabled)
    suspend fun setSmsOnlyMode(enabled: Boolean) = smsPrefs.setSmsOnlyMode(enabled)
    suspend fun setAutoRetryAsSms(enabled: Boolean) = smsPrefs.setAutoRetryAsSms(enabled)
    suspend fun setPreferSmsOverIMessage(prefer: Boolean) = smsPrefs.setPreferSmsOverIMessage(prefer)
    suspend fun setSelectedSimSlot(slot: Int) = smsPrefs.setSelectedSimSlot(slot)
    suspend fun setHasCompletedInitialSmsImport(completed: Boolean) = smsPrefs.setHasCompletedInitialSmsImport(completed)
    suspend fun setLastSmsResyncVersion(versionCode: Int) = smsPrefs.setLastSmsResyncVersion(versionCode)
    suspend fun setBlockUnknownSenders(enabled: Boolean) = smsPrefs.setBlockUnknownSenders(enabled)

    // ===== UI Preferences (delegated to UiPreferences) =====

    val useDynamicColor: Flow<Boolean> get() = uiPrefs.useDynamicColor
    val useSimpleAppTitle: Flow<Boolean> get() = uiPrefs.useSimpleAppTitle
    val denseChatTiles: Flow<Boolean> get() = uiPrefs.denseChatTiles
    val use24HourFormat: Flow<Boolean> get() = uiPrefs.use24HourFormat
    val showDeliveryTimestamps: Flow<Boolean> get() = uiPrefs.showDeliveryTimestamps
    val sendWithReturn: Flow<Boolean> get() = uiPrefs.sendWithReturn
    val autoOpenKeyboard: Flow<Boolean> get() = uiPrefs.autoOpenKeyboard
    val enablePrivateApi: Flow<Boolean> get() = uiPrefs.enablePrivateApi
    val sendTypingIndicators: Flow<Boolean> get() = uiPrefs.sendTypingIndicators
    val hasShownPrivateApiPrompt: Flow<Boolean> get() = uiPrefs.hasShownPrivateApiPrompt
    val hasCompletedSendModeTutorial: Flow<Boolean> get() = uiPrefs.hasCompletedSendModeTutorial
    val swipeGesturesEnabled: Flow<Boolean> get() = uiPrefs.swipeGesturesEnabled
    val swipeLeftAction: Flow<String> get() = uiPrefs.swipeLeftAction
    val swipeRightAction: Flow<String> get() = uiPrefs.swipeRightAction
    val swipeSensitivity: Flow<Float> get() = uiPrefs.swipeSensitivity
    val preferredCallMethod: Flow<String> get() = uiPrefs.preferredCallMethod
    val dismissedSaveContactBanners: Flow<Set<String>> get() = uiPrefs.dismissedSaveContactBanners
    val dismissedSetupBanner: Flow<Boolean> get() = uiPrefs.dismissedSetupBanner
    val dismissedSmsBanner: Flow<Boolean> get() = uiPrefs.dismissedSmsBanner
    val autoPlayEffects: Flow<Boolean> get() = uiPrefs.autoPlayEffects
    val replayEffectsOnScroll: Flow<Boolean> get() = uiPrefs.replayEffectsOnScroll
    val reduceMotion: Flow<Boolean> get() = uiPrefs.reduceMotion
    val messageSoundsEnabled: Flow<Boolean> get() = uiPrefs.messageSoundsEnabled
    val soundTheme: Flow<SoundTheme> get() = uiPrefs.soundTheme
    val conversationFilter: Flow<String> get() = uiPrefs.conversationFilter
    val categoryFilter: Flow<String?> get() = uiPrefs.categoryFilter

    suspend fun setUseDynamicColor(enabled: Boolean) = uiPrefs.setUseDynamicColor(enabled)
    suspend fun setUseSimpleAppTitle(enabled: Boolean) = uiPrefs.setUseSimpleAppTitle(enabled)
    suspend fun setDenseChatTiles(enabled: Boolean) = uiPrefs.setDenseChatTiles(enabled)
    suspend fun setUse24HourFormat(enabled: Boolean) = uiPrefs.setUse24HourFormat(enabled)
    suspend fun setShowDeliveryTimestamps(enabled: Boolean) = uiPrefs.setShowDeliveryTimestamps(enabled)
    suspend fun setSendWithReturn(enabled: Boolean) = uiPrefs.setSendWithReturn(enabled)
    suspend fun setAutoOpenKeyboard(enabled: Boolean) = uiPrefs.setAutoOpenKeyboard(enabled)
    suspend fun setEnablePrivateApi(enabled: Boolean) = uiPrefs.setEnablePrivateApi(enabled)
    suspend fun setSendTypingIndicators(enabled: Boolean) = uiPrefs.setSendTypingIndicators(enabled)
    suspend fun setHasShownPrivateApiPrompt(shown: Boolean) = uiPrefs.setHasShownPrivateApiPrompt(shown)
    suspend fun setHasCompletedSendModeTutorial(completed: Boolean) = uiPrefs.setHasCompletedSendModeTutorial(completed)
    suspend fun setSwipeGesturesEnabled(enabled: Boolean) = uiPrefs.setSwipeGesturesEnabled(enabled)
    suspend fun setSwipeLeftAction(action: String) = uiPrefs.setSwipeLeftAction(action)
    suspend fun setSwipeRightAction(action: String) = uiPrefs.setSwipeRightAction(action)
    suspend fun setSwipeSensitivity(sensitivity: Float) = uiPrefs.setSwipeSensitivity(sensitivity)
    suspend fun setPreferredCallMethod(method: String) = uiPrefs.setPreferredCallMethod(method)
    suspend fun dismissSaveContactBanner(address: String) = uiPrefs.dismissSaveContactBanner(address)
    suspend fun setDismissedSetupBanner(dismissed: Boolean) = uiPrefs.setDismissedSetupBanner(dismissed)
    suspend fun resetSetupBannerDismissal() = uiPrefs.resetSetupBannerDismissal()
    suspend fun setDismissedSmsBanner(dismissed: Boolean) = uiPrefs.setDismissedSmsBanner(dismissed)
    suspend fun resetSmsBannerDismissal() = uiPrefs.resetSmsBannerDismissal()
    suspend fun setAutoPlayEffects(enabled: Boolean) = uiPrefs.setAutoPlayEffects(enabled)
    suspend fun setReplayEffectsOnScroll(enabled: Boolean) = uiPrefs.setReplayEffectsOnScroll(enabled)
    suspend fun setReduceMotion(enabled: Boolean) = uiPrefs.setReduceMotion(enabled)
    suspend fun setMessageSoundsEnabled(enabled: Boolean) = uiPrefs.setMessageSoundsEnabled(enabled)
    suspend fun setSoundTheme(theme: SoundTheme) = uiPrefs.setSoundTheme(theme)
    suspend fun setConversationFilter(filter: String) = uiPrefs.setConversationFilter(filter)
    suspend fun setCategoryFilter(category: String?) = uiPrefs.setCategoryFilter(category)

    // ===== Sync Preferences (delegated to SyncPreferences) =====

    val initialSyncStarted: Flow<Boolean> get() = syncPrefs.initialSyncStarted
    val initialSyncComplete: Flow<Boolean> get() = syncPrefs.initialSyncComplete
    val syncedChatGuids: Flow<Set<String>> get() = syncPrefs.syncedChatGuids
    val initialSyncMessagesPerChat: Flow<Int> get() = syncPrefs.initialSyncMessagesPerChat
    val lastOpenChatGuid: Flow<String?> get() = syncPrefs.lastOpenChatGuid
    val lastOpenChatMergedGuids: Flow<String?> get() = syncPrefs.lastOpenChatMergedGuids
    val lastScrollPosition: Flow<Int> get() = syncPrefs.lastScrollPosition
    val lastScrollOffset: Flow<Int> get() = syncPrefs.lastScrollOffset
    val appLaunchTimestamps: Flow<List<Long>> get() = syncPrefs.appLaunchTimestamps

    suspend fun setInitialSyncStarted(started: Boolean) = syncPrefs.setInitialSyncStarted(started)
    suspend fun setInitialSyncComplete(complete: Boolean) = syncPrefs.setInitialSyncComplete(complete)
    suspend fun markChatSynced(chatGuid: String) = syncPrefs.markChatSynced(chatGuid)
    suspend fun setInitialSyncMessagesPerChat(messagesPerChat: Int) = syncPrefs.setInitialSyncMessagesPerChat(messagesPerChat)
    suspend fun clearSyncProgress() = syncPrefs.clearSyncProgress()
    suspend fun setLastOpenChat(chatGuid: String?, mergedGuids: String?) = syncPrefs.setLastOpenChat(chatGuid, mergedGuids)
    suspend fun setLastScrollPosition(position: Int, offset: Int) = syncPrefs.setLastScrollPosition(position, offset)
    suspend fun clearLastOpenChat() = syncPrefs.clearLastOpenChat()
    suspend fun recordLaunchAndCheckCrashProtection(): Boolean = syncPrefs.recordLaunchAndCheckCrashProtection()
    suspend fun clearLaunchTimestamps() = syncPrefs.clearLaunchTimestamps()

    // ===== Attachment Preferences (delegated to AttachmentPreferences) =====

    val autoDownloadAttachments: Flow<Boolean> get() = attachmentPrefs.autoDownloadAttachments
    val defaultImageQuality: Flow<String> get() = attachmentPrefs.defaultImageQuality
    val rememberLastQuality: Flow<Boolean> get() = attachmentPrefs.rememberLastQuality
    val videoCompressionQuality: Flow<String> get() = attachmentPrefs.videoCompressionQuality
    val compressVideosBeforeUpload: Flow<Boolean> get() = attachmentPrefs.compressVideosBeforeUpload
    val maxConcurrentDownloads: Flow<Int> get() = attachmentPrefs.maxConcurrentDownloads

    suspend fun setAutoDownloadAttachments(enabled: Boolean) = attachmentPrefs.setAutoDownloadAttachments(enabled)
    suspend fun setDefaultImageQuality(quality: String) = attachmentPrefs.setDefaultImageQuality(quality)
    suspend fun setRememberLastQuality(enabled: Boolean) = attachmentPrefs.setRememberLastQuality(enabled)
    suspend fun setVideoCompressionQuality(quality: String) = attachmentPrefs.setVideoCompressionQuality(quality)
    suspend fun setCompressVideosBeforeUpload(enabled: Boolean) = attachmentPrefs.setCompressVideosBeforeUpload(enabled)
    suspend fun setMaxConcurrentDownloads(count: Int) = attachmentPrefs.setMaxConcurrentDownloads(count)

    // ===== Feature Preferences (delegated to FeaturePreferences) =====

    val spamDetectionEnabled: Flow<Boolean> get() = featurePrefs.spamDetectionEnabled
    val spamThreshold: Flow<Int> get() = featurePrefs.spamThreshold
    val mlModelDownloaded: Flow<Boolean> get() = featurePrefs.mlModelDownloaded
    val mlAutoUpdateOnCellular: Flow<Boolean> get() = featurePrefs.mlAutoUpdateOnCellular
    val categorizationEnabled: Flow<Boolean> get() = featurePrefs.categorizationEnabled
    val developerModeEnabled: Flow<Boolean> get() = featurePrefs.developerModeEnabled
    val autoResponderEnabled: Flow<Boolean> get() = featurePrefs.autoResponderEnabled
    val autoResponderFilter: Flow<String> get() = featurePrefs.autoResponderFilter
    val autoResponderRateLimit: Flow<Int> get() = featurePrefs.autoResponderRateLimit

    suspend fun setSpamDetectionEnabled(enabled: Boolean) = featurePrefs.setSpamDetectionEnabled(enabled)
    suspend fun setSpamThreshold(threshold: Int) = featurePrefs.setSpamThreshold(threshold)
    suspend fun setMlModelDownloaded(downloaded: Boolean) = featurePrefs.setMlModelDownloaded(downloaded)
    suspend fun setMlAutoUpdateOnCellular(enabled: Boolean) = featurePrefs.setMlAutoUpdateOnCellular(enabled)
    suspend fun setCategorizationEnabled(enabled: Boolean) = featurePrefs.setCategorizationEnabled(enabled)
    suspend fun setDeveloperModeEnabled(enabled: Boolean) = featurePrefs.setDeveloperModeEnabled(enabled)
    suspend fun setAutoResponderEnabled(enabled: Boolean) = featurePrefs.setAutoResponderEnabled(enabled)
    suspend fun setAutoResponderFilter(filter: String) = featurePrefs.setAutoResponderFilter(filter)
    suspend fun setAutoResponderRateLimit(limit: Int) = featurePrefs.setAutoResponderRateLimit(limit)

    // ===== Global Operations =====

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
