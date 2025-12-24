package com.bothbubbles.ui.components.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bothbubbles.core.data.prefs.FeaturePreferences
import com.bothbubbles.services.socialmedia.SocialMediaDownloadService
import com.bothbubbles.services.socialmedia.SocialMediaDownloader
import com.bothbubbles.services.socialmedia.SocialMediaPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for managing social media link state.
 * Tracks which links have been dismissed and whether downloading is enabled.
 */
@HiltViewModel
class SocialMediaLinkViewModel @Inject constructor(
    private val downloader: SocialMediaDownloadService,
    private val featurePreferences: FeaturePreferences
) : ViewModel() {

    // Track dismissed links per message (messageGuid:url -> dismissed)
    private val _dismissedLinks = MutableStateFlow<Set<String>>(emptySet())
    val dismissedLinks: StateFlow<Set<String>> = _dismissedLinks.asStateFlow()

    /**
     * Detects the platform for a URL.
     */
    fun detectPlatform(url: String): SocialMediaPlatform? {
        val platform = downloader.detectPlatform(url)
        Timber.w("[SmartLink] detectPlatform($url) = $platform")
        return platform
    }

    /**
     * Checks if downloading is enabled for the platform.
     */
    suspend fun isDownloadEnabled(platform: SocialMediaPlatform): Boolean {
        return when (platform) {
            SocialMediaPlatform.TIKTOK -> featurePreferences.tiktokDownloaderEnabled.first()
            SocialMediaPlatform.INSTAGRAM -> featurePreferences.instagramDownloaderEnabled.first()
        }
    }

    /**
     * Checks if a specific link has been dismissed.
     */
    fun isLinkDismissed(messageGuid: String, url: String): Boolean {
        val key = "$messageGuid:$url"
        return _dismissedLinks.value.contains(key) || downloader.isLinkDismissed(messageGuid, url)
    }

    /**
     * Dismisses a link so it won't auto-download in the future.
     */
    fun dismissLink(messageGuid: String, url: String) {
        val key = "$messageGuid:$url"
        _dismissedLinks.value = _dismissedLinks.value + key
        downloader.dismissLink(messageGuid, url)
    }

    /**
     * Gets the downloader service.
     */
    fun getDownloader(): SocialMediaDownloadService = downloader

    /**
     * Checks if a cached video exists for this URL.
     */
    suspend fun hasCachedVideo(url: String): Boolean {
        return downloader.getCachedVideoPath(url) != null
    }

    /**
     * Un-dismisses a link to show the cached video player again.
     */
    fun undismissLink(messageGuid: String, url: String) {
        val key = "$messageGuid:$url"
        _dismissedLinks.value = _dismissedLinks.value - key
        downloader.clearDismissedLink(messageGuid, url)
    }
}

/**
 * A smart link preview that detects social media links and shows either:
 * 1. The video player for supported platforms (when downloading is enabled)
 * 2. The regular link preview (when downloading is disabled or link was dismissed)
 *
 * @param url The URL to preview
 * @param messageGuid The GUID of the message containing this URL
 * @param isFromMe Whether the message is from the current user
 * @param modifier Modifier for the composable
 * @param maxWidth Maximum width for the preview
 * @param onLongPress Optional callback for long press
 */
@Composable
fun SmartLinkPreview(
    url: String,
    messageGuid: String,
    chatGuid: String?,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
    onLongPress: (() -> Unit)? = null,
    onOpenReelsFeed: (() -> Unit)? = null,
    swipeProgress: Float = 0f,
    viewModel: SocialMediaLinkViewModel = hiltViewModel(),
    linkPreviewViewModel: LinkPreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissedLinks by viewModel.dismissedLinks.collectAsStateWithLifecycle()

    // Detect platform
    val platform = remember(url) { viewModel.detectPlatform(url) }

    // Track if we should show video player vs link preview
    var showVideoPlayer by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }
    var hasCached by remember { mutableStateOf(false) }

    // Check if downloading is enabled and link isn't dismissed
    LaunchedEffect(url, messageGuid, platform, dismissedLinks) {
        if (platform != null) {
            val isEnabled = viewModel.isDownloadEnabled(platform)
            val isLinkDismissed = viewModel.isLinkDismissed(messageGuid, url)
            val isCached = viewModel.hasCachedVideo(url)
            showVideoPlayer = isEnabled && !isLinkDismissed
            isDismissed = isLinkDismissed
            hasCached = isCached
        } else {
            showVideoPlayer = false
        }
    }

    if (showVideoPlayer && platform != null) {
        // Show social media video player
        SocialMediaVideoPlayer(
            url = url,
            messageGuid = messageGuid,
            chatGuid = chatGuid,
            platform = platform,
            downloader = viewModel.getDownloader(),
            isFromMe = isFromMe,
            onShowOriginal = {
                viewModel.dismissLink(messageGuid, url)
                showVideoPlayer = false
                isDismissed = true
            },
            onOpenInBrowser = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            },
            onOpenReelsFeed = onOpenReelsFeed,
            onLongPress = onLongPress,
            swipeProgress = swipeProgress,
            modifier = modifier
        )
    } else {
        // Show regular link preview with optional "Show Cached" link
        Column(
            horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
            modifier = modifier
        ) {
            BorderlessLinkPreview(
                url = url,
                isFromMe = isFromMe,
                maxWidth = maxWidth,
                onLongPress = onLongPress,
                viewModel = linkPreviewViewModel
            )

            // Show "Show Cached" link if video is cached but dismissed
            // Fades out during swipe-to-reveal gesture
            if (isDismissed && hasCached && platform != null) {
                Text(
                    text = "Show Cached",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .alpha(1f - swipeProgress)
                        .clickable {
                            viewModel.undismissLink(messageGuid, url)
                            showVideoPlayer = true
                            isDismissed = false
                        }
                        .padding(
                            start = if (isFromMe) 0.dp else 12.dp,
                            end = if (isFromMe) 12.dp else 0.dp,
                            top = 4.dp
                        )
                )
            }
        }
    }
}

/**
 * Checks if a URL is a supported social media link that can be downloaded.
 * Use this to determine if you should show SmartLinkPreview vs regular LinkPreview.
 */
fun isSocialMediaUrl(url: String, downloader: SocialMediaDownloader): Boolean {
    return downloader.detectPlatform(url) != null
}
