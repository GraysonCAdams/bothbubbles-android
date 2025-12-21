package com.bothbubbles.ui.chat.state

import androidx.compose.runtime.Stable
import com.bothbubbles.core.model.Life360Member
import com.bothbubbles.ui.util.StableList
import com.bothbubbles.ui.util.toStable

/**
 * State owned by ChatInfoDelegate.
 * Contains static or semi-static chat metadata that doesn't change frequently.
 */
@Stable
data class ChatInfoState(
    val chatTitle: String = "",
    val isGroup: Boolean = false,
    val avatarPath: String? = null,
    /** Group photo path with priority: customAvatarPath > serverGroupPhotoPath > null (use collage) */
    val groupPhotoPath: String? = null,
    val participantNames: StableList<String> = emptyList<String>().toStable(),
    val participantAvatarPaths: StableList<String?> = emptyList<String?>().toStable(),
    val participantAddresses: StableList<String> = emptyList<String>().toStable(),
    val participantFirstNames: StableList<String?> = emptyList<String?>().toStable(),
    val participantPhone: String? = null,
    val isLocalSmsChat: Boolean = false,
    val smsInputBlocked: Boolean = false,
    val isIMessageChat: Boolean = false,
    val showSaveContactBanner: Boolean = false,
    val unsavedSenderAddress: String? = null,
    val inferredSenderName: String? = null,
    val isSnoozed: Boolean = false,
    val snoozeUntil: Long? = null,
    val discordChannelId: String? = null,
    /** Life360 location subtext (e.g., "At Home" or "123 Main St"), null if unavailable or stale */
    val locationSubtext: String? = null,
    /** Life360 member data for full-screen map navigation, null if not linked or unavailable */
    val life360Member: Life360Member? = null
)
