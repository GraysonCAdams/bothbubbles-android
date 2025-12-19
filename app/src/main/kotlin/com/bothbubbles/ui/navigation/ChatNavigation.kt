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
import com.bothbubbles.ui.media.MediaViewerScreen

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
            .getStateFlow<String?>("captured_photo_uri", null)
            .collectAsStateWithLifecycle()

        // Handle shared content from share picker
        val sharedText = backStackEntry.savedStateHandle
            .getStateFlow<String?>("shared_text", null)
            .collectAsStateWithLifecycle()
        val sharedUris = backStackEntry.savedStateHandle
            .getStateFlow<ArrayList<String>?>("shared_uris", null)
            .collectAsStateWithLifecycle()

        // Handle search activation from ChatDetails screen
        val activateSearch = backStackEntry.savedStateHandle
            .getStateFlow("activate_search", false)
            .collectAsStateWithLifecycle()

        // Handle scroll position restoration
        val restoreScrollPosition = backStackEntry.savedStateHandle
            .getStateFlow("restore_scroll_position", 0)
            .collectAsStateWithLifecycle()
        val restoreScrollOffset = backStackEntry.savedStateHandle
            .getStateFlow("restore_scroll_offset", 0)
            .collectAsStateWithLifecycle()

        // Handle edited attachment
        val editedAttachmentUri = backStackEntry.savedStateHandle
            .getStateFlow<String?>("edited_attachment_uri", null)
            .collectAsStateWithLifecycle()
        val editedAttachmentCaption = backStackEntry.savedStateHandle
            .getStateFlow<String?>("edited_attachment_caption", null)
            .collectAsStateWithLifecycle()
        val originalAttachmentUri = backStackEntry.savedStateHandle
            .getStateFlow<String?>("original_attachment_uri", null)
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
                backStackEntry.savedStateHandle["original_attachment_uri"] = uri.toString()
                navController.navigate(Screen.AttachmentEdit(uri.toString()))
            },
            onLife360MapClick = { participantAddress ->
                navController.navigate(Screen.Life360Map(participantAddress))
            },
            capturedPhotoUri = capturedPhotoUri.value?.toUri(),
            onCapturedPhotoHandled = {
                backStackEntry.savedStateHandle.remove<String>("captured_photo_uri")
            },
            editedAttachmentUri = editedAttachmentUri.value?.toUri(),
            editedAttachmentCaption = editedAttachmentCaption.value,
            originalAttachmentUri = originalAttachmentUri.value?.toUri(),
            onEditedAttachmentHandled = {
                backStackEntry.savedStateHandle.remove<String>("edited_attachment_uri")
                backStackEntry.savedStateHandle.remove<String>("edited_attachment_caption")
                backStackEntry.savedStateHandle.remove<String>("original_attachment_uri")
            },
            sharedText = sharedText.value,
            sharedUris = sharedUris.value?.map { it.toUri() } ?: emptyList(),
            onSharedContentHandled = {
                backStackEntry.savedStateHandle.remove<String>("shared_text")
                backStackEntry.savedStateHandle.remove<ArrayList<String>>("shared_uris")
            },
            activateSearch = activateSearch.value,
            onSearchActivated = {
                backStackEntry.savedStateHandle["activate_search"] = false
            },
            initialScrollPosition = restoreScrollPosition.value,
            initialScrollOffset = restoreScrollOffset.value,
            onScrollPositionRestored = {
                backStackEntry.savedStateHandle.remove<Int>("restore_scroll_position")
                backStackEntry.savedStateHandle.remove<Int>("restore_scroll_offset")
            },
            targetMessageGuid = route.targetMessageGuid
        )
    }

    // New chat creator
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
                navController.previousBackStackEntry?.savedStateHandle?.set("activate_search", true)
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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(placeUrl))
                context.startActivity(intent)
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
                    set("original_attachment_uri", route.uri)
                    set("edited_attachment_uri", editedUri.toString())
                    if (caption != null) {
                        set("edited_attachment_caption", caption)
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
