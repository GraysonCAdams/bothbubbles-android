package com.bothbubbles.ui.settings.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.data.model.AttachmentQuality
import com.bothbubbles.ui.settings.SettingsViewModel
import com.bothbubbles.ui.settings.attachments.ImageQualitySettingsViewModel
import com.bothbubbles.ui.settings.components.SettingsCard
import com.bothbubbles.ui.settings.components.SettingsMenuItem
import com.bothbubbles.ui.settings.components.SettingsSectionTitle
import com.bothbubbles.ui.settings.components.SettingsSwitch
import com.bothbubbles.ui.settings.socialmedia.ReelsConceptPreview

/**
 * Combined Media & Content settings page.
 * Groups link previews, social media videos, and image quality settings.
 */
@Composable
fun MediaContentScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    imageQualityViewModel: ImageQualitySettingsViewModel = hiltViewModel()
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val imageQualityState by imageQualityViewModel.uiState.collectAsStateWithLifecycle()

    // Determine section enabled states
    val downloadSectionEnabled = uiState.tiktokDownloaderEnabled || uiState.instagramDownloaderEnabled
    val tiktokQualityEnabled = uiState.tiktokDownloaderEnabled
    val reelsSectionEnabled = uiState.socialMediaBackgroundDownloadEnabled

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════
        // SECTION: Link Previews
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Link previews")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SettingsMenuItem(
                    icon = Icons.Default.Link,
                    title = "Show link previews",
                    subtitle = if (uiState.linkPreviewsEnabled) {
                        "Show rich previews for URLs in messages"
                    } else {
                        "Disabled to improve performance"
                    },
                    onClick = { settingsViewModel.setLinkPreviewsEnabled(!uiState.linkPreviewsEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.linkPreviewsEnabled,
                            onCheckedChange = { settingsViewModel.setLinkPreviewsEnabled(it) },
                            showIcons = false
                        )
                    }
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Social Media Videos
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Social media videos")
        }

        // Reels concept preview
        item {
            ReelsConceptPreview(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // TikTok video downloading
                SettingsMenuItem(
                    icon = Icons.Default.PlayCircle,
                    title = "TikTok video playback",
                    subtitle = if (uiState.tiktokDownloaderEnabled) {
                        "Play TikTok videos inline"
                    } else {
                        "Open TikTok links in browser"
                    },
                    onClick = { settingsViewModel.setTiktokDownloaderEnabled(!uiState.tiktokDownloaderEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.tiktokDownloaderEnabled,
                            onCheckedChange = settingsViewModel::setTiktokDownloaderEnabled,
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Instagram video downloading
                SettingsMenuItem(
                    icon = Icons.Default.PlayCircle,
                    title = "Instagram video playback",
                    subtitle = if (uiState.instagramDownloaderEnabled) {
                        "Play Instagram Reels inline"
                    } else {
                        "Open Instagram links in browser"
                    },
                    onClick = { settingsViewModel.setInstagramDownloaderEnabled(!uiState.instagramDownloaderEnabled) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = uiState.instagramDownloaderEnabled,
                            onCheckedChange = settingsViewModel::setInstagramDownloaderEnabled,
                            showIcons = false
                        )
                    }
                )
            }
        }

        // Download behavior section (only if platforms enabled)
        if (downloadSectionEnabled) {
            item {
                SettingsSectionTitle(title = "Download behavior")
            }

            item {
                SettingsCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // Auto-download videos
                    SettingsMenuItem(
                        icon = Icons.Default.CloudDownload,
                        title = "Auto-download videos",
                        subtitle = if (uiState.socialMediaBackgroundDownloadEnabled) {
                            "Videos download automatically when received"
                        } else {
                            "Videos download only when you tap to play"
                        },
                        onClick = {
                            settingsViewModel.setSocialMediaBackgroundDownloadEnabled(!uiState.socialMediaBackgroundDownloadEnabled)
                        },
                        trailingContent = {
                            SettingsSwitch(
                                checked = uiState.socialMediaBackgroundDownloadEnabled,
                                onCheckedChange = settingsViewModel::setSocialMediaBackgroundDownloadEnabled,
                                showIcons = false
                            )
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Download on cellular
                    SettingsMenuItem(
                        icon = Icons.Default.SignalCellularAlt,
                        title = "Download on cellular",
                        subtitle = if (uiState.socialMediaDownloadOnCellularEnabled) {
                            "Downloads use Wi-Fi and mobile data"
                        } else {
                            "Downloads only on Wi-Fi"
                        },
                        onClick = {
                            settingsViewModel.setSocialMediaDownloadOnCellularEnabled(!uiState.socialMediaDownloadOnCellularEnabled)
                        },
                        trailingContent = {
                            SettingsSwitch(
                                checked = uiState.socialMediaDownloadOnCellularEnabled,
                                onCheckedChange = settingsViewModel::setSocialMediaDownloadOnCellularEnabled,
                                showIcons = false
                            )
                        }
                    )

                    if (tiktokQualityEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // TikTok video quality
                        SettingsMenuItem(
                            icon = Icons.Default.Hd,
                            title = "TikTok video quality",
                            subtitle = if (uiState.tiktokVideoQuality == "hd") {
                                "HD - Higher quality, larger downloads"
                            } else {
                                "SD - Lower quality, faster downloads"
                            },
                            onClick = {
                                val newQuality = if (uiState.tiktokVideoQuality == "hd") "sd" else "hd"
                                settingsViewModel.setTiktokVideoQuality(newQuality)
                            },
                            trailingContent = {
                                Text(
                                    text = if (uiState.tiktokVideoQuality == "hd") "HD" else "SD",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                }
            }

            // Viewing experience section (only if auto-download enabled)
            if (reelsSectionEnabled) {
                item {
                    SettingsSectionTitle(title = "Viewing experience")
                }

                item {
                    SettingsCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // Reels feed
                        SettingsMenuItem(
                            icon = Icons.Default.Slideshow,
                            title = "Reels experience",
                            subtitle = if (uiState.reelsFeedEnabled) {
                                "Swipe vertically through videos full-screen"
                            } else {
                                "Videos play inline in the chat"
                            },
                            onClick = { settingsViewModel.setReelsFeedEnabled(!uiState.reelsFeedEnabled) },
                            trailingContent = {
                                SettingsSwitch(
                                    checked = uiState.reelsFeedEnabled,
                                    onCheckedChange = settingsViewModel::setReelsFeedEnabled,
                                    showIcons = false
                                )
                            }
                        )

                        if (uiState.reelsFeedEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Include video attachments in reels
                            SettingsMenuItem(
                                icon = Icons.Default.AttachFile,
                                title = "Include video attachments",
                                subtitle = if (uiState.reelsIncludeVideoAttachments) {
                                    "All videos in chat appear in Reels"
                                } else {
                                    "Only social media videos in Reels"
                                },
                                onClick = {
                                    settingsViewModel.setReelsIncludeVideoAttachments(!uiState.reelsIncludeVideoAttachments)
                                },
                                trailingContent = {
                                    SettingsSwitch(
                                        checked = uiState.reelsIncludeVideoAttachments,
                                        onCheckedChange = settingsViewModel::setReelsIncludeVideoAttachments,
                                        showIcons = false
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SECTION: Image Quality
        // ═══════════════════════════════════════════════════════════════
        item {
            SettingsSectionTitle(title = "Image quality")
        }

        item {
            Text(
                text = "Choose the default quality for images you send. Higher quality means larger files and slower uploads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                AttachmentQuality.entries.forEachIndexed { index, quality ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    val icon = when (quality) {
                        AttachmentQuality.AUTO -> Icons.Outlined.Tune
                        AttachmentQuality.STANDARD -> Icons.Outlined.Image
                        AttachmentQuality.HIGH -> Icons.Outlined.HighQuality
                        AttachmentQuality.ORIGINAL -> Icons.Outlined.PhotoSizeSelectLarge
                    }

                    val isSelected = quality == imageQualityState.selectedQuality

                    SettingsMenuItem(
                        icon = icon,
                        title = quality.displayName,
                        subtitle = quality.description,
                        onClick = { imageQualityViewModel.setImageQuality(quality) },
                        trailingContent = if (isSelected) {
                            {
                                androidx.compose.material3.Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null
                    )
                }
            }
        }

        // Image quality behavior section
        item {
            SettingsSectionTitle(title = "Image behavior")
        }

        item {
            SettingsCard(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SettingsMenuItem(
                    icon = Icons.Default.Image,
                    title = "Remember last quality",
                    subtitle = "When you change quality while composing, use that quality for subsequent messages",
                    onClick = { imageQualityViewModel.setRememberLastQuality(!imageQualityState.rememberLastQuality) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = imageQualityState.rememberLastQuality,
                            onCheckedChange = imageQualityViewModel::setRememberLastQuality,
                            showIcons = false
                        )
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SettingsMenuItem(
                    icon = Icons.Default.PhotoSizeSelectLarge,
                    title = "Save photos to gallery",
                    subtitle = "Automatically save photos and videos taken in-app to your camera roll",
                    onClick = { imageQualityViewModel.setSaveCapturedMediaToGallery(!imageQualityState.saveCapturedMediaToGallery) },
                    trailingContent = {
                        SettingsSwitch(
                            checked = imageQualityState.saveCapturedMediaToGallery,
                            onCheckedChange = imageQualityViewModel::setSaveCapturedMediaToGallery,
                            showIcons = false
                        )
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
