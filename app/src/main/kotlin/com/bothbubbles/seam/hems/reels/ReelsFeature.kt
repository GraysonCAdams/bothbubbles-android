package com.bothbubbles.seam.hems.reels

import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.di.ApplicationScope
import com.bothbubbles.seam.hems.Feature
import com.bothbubbles.seam.settings.SettingsContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReelsFeature provides a TikTok-style vertical swipe feed for social media links
 * extracted from messages.
 *
 * This Feature (Hem) works across all Stitches - it processes messages from
 * both iMessage and SMS to find video links.
 *
 * User-facing name: "Reels" (brand term)
 * Code name: "ReelsFeature" (implements Feature interface)
 *
 * The Reels feed aggregates:
 * - TikTok videos (when enabled)
 * - Instagram Reels (when enabled)
 * - Regular video attachments (when enabled)
 *
 * Videos are cached locally for offline viewing and organized chronologically
 * with unwatched content surfaced first.
 *
 * ## Settings Integration
 * Reels settings are integrated into the "Media & content" settings page rather
 * than having a dedicated settings screen. The settings are managed by
 * [SocialMediaSettingsContent] and include:
 * - Reels experience toggle
 * - Include video attachments toggle
 */
@Singleton
class ReelsFeature @Inject constructor(
    private val featurePreferences: FeaturePreferences,
    @ApplicationScope private val applicationScope: CoroutineScope
) : Feature {

    companion object {
        const val ID = "reels"
        const val DISPLAY_NAME = "Reels Feed"
        const val DESCRIPTION = "View TikTok and Instagram videos in a swipeable feed"
        const val FEATURE_FLAG_KEY = "reels_feed_enabled"
    }

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val description: String = DESCRIPTION
    override val featureFlagKey: String = FEATURE_FLAG_KEY

    override val isEnabled: StateFlow<Boolean> =
        featurePreferences.reelsFeedEnabled.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    @Deprecated("Use settingsContribution instead", ReplaceWith("settingsContribution"))
    override val settingsRoute: String? = null

    /**
     * Reels settings are part of "Media & content" settings, not a dedicated page.
     * No dedicated menu item is needed.
     */
    override val settingsContribution: SettingsContribution = SettingsContribution.NONE

    override suspend fun onEnable() {
        // Called when feature is enabled
        // The actual enabling logic is handled by ChatReelsDelegate.enableReels()
        // which sets all the related preferences
    }

    override suspend fun onDisable() {
        // Called when feature is disabled
        // The cache is managed by SocialMediaCacheManager and can be cleared
        // through the storage management settings if needed
    }
}
