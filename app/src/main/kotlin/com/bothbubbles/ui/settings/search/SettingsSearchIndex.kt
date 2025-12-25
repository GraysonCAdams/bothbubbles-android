package com.bothbubbles.ui.settings.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MarkUnreadChatAlt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Quickreply
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.ui.graphics.vector.ImageVector
import com.bothbubbles.ui.settings.SettingsPanelPage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Represents a searchable settings item with metadata for search and navigation.
 */
data class SearchableSettingsItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val keywords: List<String>,
    val section: String,
    val page: SettingsPanelPage,
    val icon: ImageVector
)

/**
 * Static index of all searchable settings with keywords for discovery.
 */
object SettingsSearchIndex {

    val items: ImmutableList<SearchableSettingsItem> = persistentListOf(
        // ═══════════════════════════════════════════════════════════════
        // Connection & server
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "imessage",
            title = "iMessage",
            subtitle = "BlueBubbles server settings",
            keywords = listOf("server", "bluebubbles", "connection", "mac", "apple", "imessage"),
            section = "Connection & server",
            page = SettingsPanelPage.Server,
            icon = Icons.Default.Cloud
        ),
        SearchableSettingsItem(
            id = "private_api",
            title = "Enable Private API",
            subtitle = "Advanced iMessage features",
            keywords = listOf("typing", "reactions", "tapback", "read receipts", "edit", "unsend", "scheduled"),
            section = "Connection & server",
            page = SettingsPanelPage.Server,
            icon = Icons.Default.VpnKey
        ),
        SearchableSettingsItem(
            id = "typing_indicators",
            title = "Send typing indicators",
            subtitle = "Let others know when you're typing",
            keywords = listOf("typing", "status", "indicator"),
            section = "Connection & server",
            page = SettingsPanelPage.Server,
            icon = Icons.Default.Keyboard
        ),
        SearchableSettingsItem(
            id = "sms",
            title = "SMS/MMS",
            subtitle = "Local SMS messaging options",
            keywords = listOf("text", "message", "cellular", "carrier", "sms", "mms", "default"),
            section = "Connection & server",
            page = SettingsPanelPage.Sms,
            icon = Icons.Default.CellTower
        ),
        SearchableSettingsItem(
            id = "sync",
            title = "Sync settings",
            subtitle = "Sync options and timing",
            keywords = listOf("sync", "refresh", "update", "download"),
            section = "Connection & server",
            page = SettingsPanelPage.Sync,
            icon = Icons.Default.Sync
        ),

        // ═══════════════════════════════════════════════════════════════
        // Notifications & alerts
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "notifications",
            title = "Notifications",
            subtitle = "Sound, vibration, and display",
            keywords = listOf("alert", "notification", "sound", "vibrate", "badge", "push"),
            section = "Notifications & alerts",
            page = SettingsPanelPage.Notifications,
            icon = Icons.Default.Notifications
        ),
        SearchableSettingsItem(
            id = "message_sounds",
            title = "Message sounds",
            subtitle = "Play sounds when sending and receiving",
            keywords = listOf("audio", "sound", "tone", "volume"),
            section = "Notifications & alerts",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.VolumeUp
        ),
        SearchableSettingsItem(
            id = "sound_theme",
            title = "Sound theme",
            subtitle = "Choose notification sounds",
            keywords = listOf("audio", "sound", "tone", "theme", "ringtone"),
            section = "Notifications & alerts",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.MusicNote
        ),

        // ═══════════════════════════════════════════════════════════════
        // Appearance & interaction
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "app_title",
            title = "Simple app title",
            subtitle = "Show Messages vs BothBubbles",
            keywords = listOf("title", "name", "header", "branding", "messages"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.TextFields
        ),
        SearchableSettingsItem(
            id = "unread_count",
            title = "Show unread count",
            subtitle = "Badge visible in header",
            keywords = listOf("badge", "count", "unread", "notification"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.MarkUnreadChatAlt
        ),
        SearchableSettingsItem(
            id = "effects",
            title = "Message effects",
            subtitle = "Animations for screen and bubble effects",
            keywords = listOf("animation", "effect", "celebration", "slam", "loud", "gentle", "confetti"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Effects,
            icon = Icons.Default.AutoAwesome
        ),
        SearchableSettingsItem(
            id = "swipe",
            title = "Swipe actions",
            subtitle = "Customize conversation swipe gestures",
            keywords = listOf("swipe", "gesture", "archive", "delete", "pin", "mute", "quick"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Swipe,
            icon = Icons.Default.SwipeRight
        ),
        SearchableSettingsItem(
            id = "haptics",
            title = "Haptic feedback",
            subtitle = "Vibration feedback",
            keywords = listOf("haptic", "vibration", "feedback", "touch", "vibrate"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.Vibration
        ),
        SearchableSettingsItem(
            id = "audio_haptic_sync",
            title = "Sync haptics with sounds",
            subtitle = "Play haptic patterns matched to sound effects",
            keywords = listOf("haptic", "audio", "sync", "pattern"),
            section = "Appearance & interaction",
            page = SettingsPanelPage.Main,
            icon = Icons.Default.GraphicEq
        ),

        // ═══════════════════════════════════════════════════════════════
        // Messaging
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "templates",
            title = "Quick reply templates",
            subtitle = "Saved responses and smart suggestions",
            keywords = listOf("template", "quick", "reply", "canned", "saved", "response"),
            section = "Messaging",
            page = SettingsPanelPage.Templates,
            icon = Icons.Default.Quickreply
        ),
        SearchableSettingsItem(
            id = "auto_responder",
            title = "Auto-responder",
            subtitle = "Greet first-time iMessage contacts",
            keywords = listOf("auto", "response", "automatic", "reply", "greeting", "away"),
            section = "Messaging",
            page = SettingsPanelPage.AutoResponder,
            icon = Icons.Default.SmartToy
        ),
        SearchableSettingsItem(
            id = "media_content",
            title = "Media & content",
            subtitle = "Link previews, social media videos, image quality",
            keywords = listOf("media", "content", "link", "preview", "video", "image", "quality", "tiktok", "instagram"),
            section = "Messaging",
            page = SettingsPanelPage.MediaContent,
            icon = Icons.Default.PermMedia
        ),

        // ═══════════════════════════════════════════════════════════════
        // Sharing & Social
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "eta",
            title = "ETA sharing",
            subtitle = "Share arrival time while navigating",
            keywords = listOf("eta", "arrival", "navigation", "location", "maps", "share"),
            section = "Sharing & Social",
            page = SettingsPanelPage.EtaSharing,
            icon = Icons.Outlined.Navigation
        ),
        SearchableSettingsItem(
            id = "life360",
            title = "Life360",
            subtitle = "Show friends and family locations",
            keywords = listOf("life360", "location", "family", "tracking", "friend"),
            section = "Sharing & Social",
            page = SettingsPanelPage.Life360,
            icon = Icons.Outlined.LocationOn
        ),
        SearchableSettingsItem(
            id = "calendar",
            title = "Calendar integrations",
            subtitle = "Show contact calendars in chat headers",
            keywords = listOf("calendar", "schedule", "events", "meeting", "appointment"),
            section = "Sharing & Social",
            page = SettingsPanelPage.Calendar,
            icon = Icons.Default.CalendarMonth
        ),

        // ═══════════════════════════════════════════════════════════════
        // Privacy & permissions
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "blocked",
            title = "Blocked contacts",
            subtitle = "Manage blocked numbers",
            keywords = listOf("block", "blocked", "ban", "blacklist"),
            section = "Privacy & permissions",
            page = SettingsPanelPage.Blocked,
            icon = Icons.Default.Block
        ),
        SearchableSettingsItem(
            id = "spam",
            title = "Spam protection",
            subtitle = "Automatic spam detection",
            keywords = listOf("spam", "junk", "filter", "block", "unwanted"),
            section = "Privacy & permissions",
            page = SettingsPanelPage.Spam,
            icon = Icons.Default.Shield
        ),
        SearchableSettingsItem(
            id = "categorization",
            title = "Message categorization",
            subtitle = "Sort messages with ML",
            keywords = listOf("category", "filter", "organize", "sort", "ai", "ml", "inbox"),
            section = "Privacy & permissions",
            page = SettingsPanelPage.Categorization,
            icon = Icons.Default.Category
        ),
        SearchableSettingsItem(
            id = "archived",
            title = "Archived",
            subtitle = "View archived conversations",
            keywords = listOf("archive", "hidden", "hide", "old"),
            section = "Privacy & permissions",
            page = SettingsPanelPage.Archived,
            icon = Icons.Outlined.Archive
        ),

        // ═══════════════════════════════════════════════════════════════
        // Data & backup
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "storage",
            title = "Storage",
            subtitle = "Manage cached files and app storage",
            keywords = listOf("storage", "cache", "clear", "space", "disk", "memory"),
            section = "Data & backup",
            page = SettingsPanelPage.Storage,
            icon = Icons.Default.Storage
        ),
        SearchableSettingsItem(
            id = "export",
            title = "Export messages",
            subtitle = "Save conversations as HTML or PDF",
            keywords = listOf("export", "backup", "save", "html", "pdf", "download", "archive"),
            section = "Data & backup",
            page = SettingsPanelPage.Export,
            icon = Icons.Default.Download
        ),

        // ═══════════════════════════════════════════════════════════════
        // About
        // ═══════════════════════════════════════════════════════════════
        SearchableSettingsItem(
            id = "about",
            title = "About",
            subtitle = "Version, licenses, and help",
            keywords = listOf("about", "version", "license", "help", "support", "info"),
            section = "About",
            page = SettingsPanelPage.About,
            icon = Icons.Default.Info
        )
    )

    /**
     * Search the settings index with priority-based scoring.
     * Returns results sorted by relevance score.
     */
    fun search(query: String): List<SearchableSettingsItem> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = query.lowercase().trim()

        return items
            .mapNotNull { item ->
                val score = calculateScore(item, normalizedQuery)
                if (score > 0) item to score else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun calculateScore(item: SearchableSettingsItem, query: String): Int {
        var score = 0

        // Title match (highest priority)
        val titleLower = item.title.lowercase()
        if (titleLower.contains(query)) {
            score += 100
            // Bonus for starts-with match
            if (titleLower.startsWith(query)) {
                score += 50
            }
        }

        // Keyword match
        if (item.keywords.any { it.lowercase().contains(query) }) {
            score += 50
        }

        // Subtitle match
        if (item.subtitle?.lowercase()?.contains(query) == true) {
            score += 20
        }

        // Section match (lowest priority)
        if (item.section.lowercase().contains(query)) {
            score += 10
        }

        return score
    }
}
