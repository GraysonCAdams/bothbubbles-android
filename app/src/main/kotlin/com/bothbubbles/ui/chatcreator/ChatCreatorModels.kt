package com.bothbubbles.ui.chatcreator

/**
 * UI model for displaying a contact in the list
 */
data class ContactUiModel(
    val address: String,
    val normalizedAddress: String,  // For de-duplication
    val formattedAddress: String,
    val displayName: String,
    val service: String,
    val avatarPath: String? = null,
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false  // Whether this contact has recent conversations
)
