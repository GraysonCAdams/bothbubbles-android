package com.bothbubbles.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bothbubbles.ui.chat.ChatScreen
import com.bothbubbles.ui.chat.details.ChatNotificationSettingsScreen
import com.bothbubbles.ui.chat.details.ConversationDetailsScreen
import com.bothbubbles.ui.chat.details.Life360MapScreen
import com.bothbubbles.ui.chat.details.LinksScreen
import com.bothbubbles.ui.chat.details.MediaGalleryScreen
import com.bothbubbles.ui.chat.details.MediaLinksScreen
import com.bothbubbles.ui.chat.details.PlacesScreen
import com.bothbubbles.ui.chatcreator.ChatCreatorScreen
import com.bothbubbles.ui.chatcreator.GroupCreatorScreen
import com.bothbubbles.ui.compose.ComposeScreen
import com.bothbubbles.ui.media.MediaViewerScreen
import timber.log.Timber

/**
 * Chat-related navigation routes including individual chat, chat details,
 * media gallery, camera, and chat creation.
 */
fun NavGraphBuilder.chatNavigation(navController: NavHostController) {
    // Individual chat
    composable<Screen.Chat> { backStackEntry ->
        val route: Screen.Chat = backStackEntry.toRoute()

        // Handle captured photo from camera screen
        val capturedPhotoUri = backStackEntry.savedStateHandle
            .getStateFlow<String?>(NavigationKeys.CAPTURED_PHOTO_URI, null)
            .collectAsStateWithLifecycle()

        // Handle shared content - prefer route params (direct share), fall back to savedStateHandle (share picker)
        val sharedTextFromHandle = backStackEntry.savedStateHandle
            .getStateFlow<String?>(NavigationKeys.SHARED_TEXT, null)
            .collectAsStateWithLifecycle()
        val sharedUrisFromHandle = backStackEntry.savedStateHandle
            .getStateFlow<ArrayList<String>?>(NavigationKeys.SHARED_URIS, null)
            .collectAsStateWithLifecycle()

        // Route params take precedence (from direct share), then savedStateHandle (from in-app share picker)
        val effectiveSharedText = route.sharedText ?: sharedTextFromHandle.value
        val effectiveSharedUris = route.sharedUris.ifEmpty { sharedUrisFromHandle.value ?: emptyList() }

        // Handle search activation from ChatDetails screen
        val activateSearch = backStackEntry.savedStateHandle
            .getStateFlow(NavigationKeys.ACTIVATE_SEARCH, false)
            .collectAsStateWithLifecycle()

        // Handle scroll position restoration
        val restoreScrollPosition = backStackEntry.savedStateHandle
            .getStateFlow(NavigationKeys.RESTORE_SCROLL_POSITION, 0)
            .collectAsStateWithLifecycle()
        val restoreScrollOffset = backStackEntry.savedStateHandle
            .getStateFlow(NavigationKeys.RESTORE_SCROLL_OFFSET, 0)
            .collectAsStateWithLifecycle()

        // Handle edited attachment
        val editedAttachmentUri = backStackEntry.savedStateHandle
            .getStateFlow<String?>(NavigationKeys.EDITED_ATTACHMENT_URI, null)
            .collectAsStateWithLifecycle()
        val editedAttachmentCaption = backStackEntry.savedStateHandle
            .getStateFlow<String?>(NavigationKeys.EDITED_ATTACHMENT_CAPTION, null)
            .collectAsStateWithLifecycle()
        val originalAttachmentUri = backStackEntry.savedStateHandle
            .getStateFlow<String?>(NavigationKeys.ORIGINAL_ATTACHMENT_URI, null)
            .collectAsStateWithLifecycle()

        ChatScreen(
            chatGuid = route.chatGuid,
            onBackClick = { navController.popBackStack() },
            onDetailsClick = {
                navController.navigate(Screen.ChatDetails(route.chatGuid))
            },
            onMediaClick = { attachmentGuid ->
                navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
            },
            onEditAttachmentClick = { uri ->
                // Store original URI to update after edit
                backStackEntry.savedStateHandle[NavigationKeys.ORIGINAL_ATTACHMENT_URI] = uri.toString()
                navController.navigate(Screen.AttachmentEdit(uri.toString()))
            },
            onLife360MapClick = { participantAddress ->
                navController.navigate(Screen.Life360Map(participantAddress))
            },
            capturedPhotoUri = capturedPhotoUri.value?.toUri(),
            onCapturedPhotoHandled = {
                backStackEntry.savedStateHandle.remove<String>(NavigationKeys.CAPTURED_PHOTO_URI)
            },
            editedAttachmentUri = editedAttachmentUri.value?.toUri(),
            editedAttachmentCaption = editedAttachmentCaption.value,
            originalAttachmentUri = originalAttachmentUri.value?.toUri(),
            onEditedAttachmentHandled = {
                backStackEntry.savedStateHandle.remove<String>(NavigationKeys.EDITED_ATTACHMENT_URI)
                backStackEntry.savedStateHandle.remove<String>(NavigationKeys.EDITED_ATTACHMENT_CAPTION)
                backStackEntry.savedStateHandle.remove<String>(NavigationKeys.ORIGINAL_ATTACHMENT_URI)
            },
            sharedText = effectiveSharedText,
            sharedUris = effectiveSharedUris.map { it.toUri() },
            onSharedContentHandled = {
                // Clear savedStateHandle entries (route params are immutable and don't need clearing)
                backStackEntry.savedStateHandle.remove<String>(NavigationKeys.SHARED_TEXT)
                backStackEntry.savedStateHandle.remove<ArrayList<String>>(NavigationKeys.SHARED_URIS)
            },
            activateSearch = activateSearch.value,
            onSearchActivated = {
                backStackEntry.savedStateHandle[NavigationKeys.ACTIVATE_SEARCH] = false
            },
            initialScrollPosition = restoreScrollPosition.value,
            initialScrollOffset = restoreScrollOffset.value,
            onScrollPositionRestored = {
                backStackEntry.savedStateHandle.remove<Int>(NavigationKeys.RESTORE_SCROLL_POSITION)
                backStackEntry.savedStateHandle.remove<Int>(NavigationKeys.RESTORE_SCROLL_OFFSET)
            },
            targetMessageGuid = route.targetMessageGuid
        )
    }

    // New chat creator (legacy - kept for backwards compatibility)
    composable<Screen.ChatCreator> { backStackEntry ->
        val route: Screen.ChatCreator = backStackEntry.toRoute()
        ChatCreatorScreen(
            onBackClick = { navController.popBackStack() },
            onChatCreated = { chatGuid ->
                navController.navigate(Screen.Chat(chatGuid)) {
                    popUpTo(Screen.Conversations) { inclusive = false }
                }
            },
            onNavigateToGroupSetup = { participantsJson, groupService ->
                navController.navigate(Screen.GroupSetup(participantsJson, groupService))
            }
        )
    }

    // Apple-style compose screen (new) - also used for sharing from other apps
    composable<Screen.Compose> { backStackEntry ->
        val route: Screen.Compose = backStackEntry.toRoute()
        val context = LocalContext.current

        Timber.d("ChatNavigation: Screen.Compose route - sharedText='${route.sharedText}', sharedUris=${route.sharedUris.size}, initialAddress=${route.initialAddress}")

        ComposeScreen(
            onNavigateBack = {
                // Try to pop back stack - if nothing to pop (opened from share intent),
                // finish the activity to return to the sharing app
                if (!navController.popBackStack()) {
                    (context as? android.app.Activity)?.finish()
                }
            },
            onNavigateToChat = { chatGuid, mergedGuids ->
                val mergedGuidsStr = if (mergedGuids != null && mergedGuids.size > 1) {
                    mergedGuids.joinToString(",")
                } else null
                navController.navigate(Screen.Chat(chatGuid, mergedGuidsStr)) {
                    popUpTo(Screen.Conversations) { inclusive = false }
                }
            },
            sharedText = route.sharedText,
            sharedUris = route.sharedUris.map { it.toUri() },
            initialAddress = route.initialAddress
        )
    }

    // Group creator (Step 1: Contact picker)
    composable<Screen.GroupCreator> {
        GroupCreatorScreen(
            onBackClick = { navController.popBackStack() },
            onNextClick = { participantsJson, groupService ->
                navController.navigate(Screen.GroupSetup(participantsJson, groupService))
            }
        )
    }

    // Group setup (Step 2: Name/photo configuration)
    composable<Screen.GroupSetup> { backStackEntry ->
        val route: Screen.GroupSetup = backStackEntry.toRoute()
        com.bothbubbles.ui.chatcreator.GroupSetupScreen(
            onBackClick = { navController.popBackStack() },
            onGroupCreated = { chatGuid ->
                navController.navigate(Screen.Chat(chatGuid)) {
                    popUpTo(Screen.Conversations) { inclusive = false }
                }
            }
        )
    }

    // Chat details
    composable<Screen.ChatDetails> { backStackEntry ->
        val route: Screen.ChatDetails = backStackEntry.toRoute()
        ConversationDetailsScreen(
            chatGuid = route.chatGuid,
            onNavigateBack = { navController.popBackStack() },
            onSearchClick = {
                // Set activate_search flag on Chat screen and pop back
                navController.previousBackStackEntry?.savedStateHandle?.set(NavigationKeys.ACTIVATE_SEARCH, true)
                navController.popBackStack()
            },
            onMediaGalleryClick = { mediaType ->
                navController.navigate(Screen.MediaGallery(route.chatGuid, mediaType))
            },
            onNotificationSettingsClick = {
                navController.navigate(Screen.ChatNotificationSettings(route.chatGuid))
            },
            onCreateGroupClick = { address, displayName, service, avatarPath ->
                navController.navigate(Screen.GroupCreator(
                    preSelectedAddress = address,
                    preSelectedDisplayName = displayName,
                    preSelectedService = service,
                    preSelectedAvatarPath = avatarPath
                ))
            },
            onLife360MapClick = { participantAddress ->
                navController.navigate(Screen.Life360Map(participantAddress))
            }
        )
    }

    // Chat notification settings
    composable<Screen.ChatNotificationSettings> { backStackEntry ->
        val route: Screen.ChatNotificationSettings = backStackEntry.toRoute()
        ChatNotificationSettingsScreen(
            chatGuid = route.chatGuid,
            onNavigateBack = { navController.popBackStack() }
        )
    }

    // Media viewer
    composable<Screen.MediaViewer> { backStackEntry ->
        MediaViewerScreen(
            onNavigateBack = { navController.popBackStack() }
        )
    }

    // Media gallery (images/videos grid or links overview)
    composable<Screen.MediaGallery> { backStackEntry ->
        val route: Screen.MediaGallery = backStackEntry.toRoute()
        if (route.mediaType == "all") {
            // Show the overview with sections for images, videos, places, links
            MediaLinksScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onMediaClick = { attachmentGuid ->
                    navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                },
                onSeeAllImages = {
                    navController.navigate(Screen.MediaGallery(route.chatGuid, "images"))
                },
                onSeeAllVideos = {
                    navController.navigate(Screen.MediaGallery(route.chatGuid, "videos"))
                },
                onSeeAllPlaces = {
                    navController.navigate(Screen.PlacesGallery(route.chatGuid))
                },
                onSeeAllLinks = {
                    navController.navigate(Screen.LinksGallery(route.chatGuid))
                }
            )
        } else {
            // Show the grid view for specific media type
            MediaGalleryScreen(
                chatGuid = route.chatGuid,
                onNavigateBack = { navController.popBackStack() },
                onMediaClick = { attachmentGuid ->
                    navController.navigate(Screen.MediaViewer(attachmentGuid, route.chatGuid))
                }
            )
        }
    }

    // Links gallery
    composable<Screen.LinksGallery> { backStackEntry ->
        val route: Screen.LinksGallery = backStackEntry.toRoute()
        val context = LocalContext.current
        LinksScreen(
            chatGuid = route.chatGuid,
            onNavigateBack = { navController.popBackStack() },
            onLinkClick = { url ->
                try {
                    val uri = Uri.parse(url)
                    if (uri.scheme in listOf("http", "https", "tel", "mailto")) {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    } else {
                        Timber.w("Unsupported URI scheme: ${uri.scheme}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse or open URI: $url")
                }
            }
        )
    }

    // Places gallery
    composable<Screen.PlacesGallery> { backStackEntry ->
        val route: Screen.PlacesGallery = backStackEntry.toRoute()
        val context = LocalContext.current
        PlacesScreen(
            chatGuid = route.chatGuid,
            onNavigateBack = { navController.popBackStack() },
            onPlaceClick = { placeUrl ->
                try {
                    val uri = Uri.parse(placeUrl)
                    if (uri.scheme in listOf("http", "https", "geo")) {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    } else {
                        Timber.w("Unsupported URI scheme: ${uri.scheme}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse or open URI: $placeUrl")
                }
            }
        )
    }

    // Attachment Edit
    composable<Screen.AttachmentEdit> { backStackEntry ->
        val route: Screen.AttachmentEdit = backStackEntry.toRoute()
        com.bothbubbles.ui.chat.composer.AttachmentEditScreen(
            uri = Uri.parse(route.uri),
            initialCaption = null,
            onSave = { editedUri, caption ->
                navController.previousBackStackEntry?.savedStateHandle?.apply {
                    set(NavigationKeys.ORIGINAL_ATTACHMENT_URI, route.uri)
                    set(NavigationKeys.EDITED_ATTACHMENT_URI, editedUri.toString())
                    if (caption != null) {
                        set(NavigationKeys.EDITED_ATTACHMENT_CAPTION, caption)
                    }
                }
                navController.popBackStack()
            },
            onCancel = { navController.popBackStack() }
        )
    }

    // Life360 full-screen map
    composable<Screen.Life360Map> {
        Life360MapScreen(
            onBack = { navController.popBackStack() }
        )
    }
}
