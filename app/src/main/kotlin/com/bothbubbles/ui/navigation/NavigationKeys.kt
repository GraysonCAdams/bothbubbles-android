package com.bothbubbles.ui.navigation

/**
 * Constants for SavedStateHandle keys used in navigation.
 * Centralizes magic strings to prevent typos and ensure consistency.
 */
object NavigationKeys {
    // Chat screen state
    const val RESTORE_SCROLL_POSITION = "restore_scroll_position"
    const val RESTORE_SCROLL_OFFSET = "restore_scroll_offset"
    const val ACTIVATE_SEARCH = "activate_search"
    const val CAPTURED_PHOTO_URI = "captured_photo_uri"
    const val SHARED_TEXT = "shared_text"
    const val SHARED_URIS = "shared_uris"
    const val EDITED_ATTACHMENT_URI = "edited_attachment_uri"
    const val EDITED_ATTACHMENT_CAPTION = "edited_attachment_caption"
    const val ORIGINAL_ATTACHMENT_URI = "original_attachment_uri"

    // Settings panel state
    const val OPEN_SETTINGS_PANEL = "open_settings_panel"
}
