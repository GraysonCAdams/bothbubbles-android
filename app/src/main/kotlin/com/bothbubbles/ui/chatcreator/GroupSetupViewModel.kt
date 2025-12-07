package com.bothbubbles.ui.chatcreator

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bothbubbles.data.local.db.dao.ChatDao
import com.bothbubbles.data.local.db.entity.ChatEntity
import com.bothbubbles.data.remote.api.BothBubblesApi
import com.bothbubbles.data.remote.api.dto.CreateChatRequest
import com.bothbubbles.data.remote.api.dto.UpdateChatRequest
import com.bothbubbles.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class GroupSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val api: BothBubblesApi
) : ViewModel() {

    companion object {
        private const val TAG = "GroupSetupViewModel"
    }

    private val route: Screen.GroupSetup = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(GroupSetupUiState())
    val uiState: StateFlow<GroupSetupUiState> = _uiState.asStateFlow()

    init {
        // Parse participants from navigation
        try {
            val participants = Json.decodeFromString<List<GroupParticipant>>(route.participantsJson)
            val groupService = GroupServiceType.valueOf(route.groupService)
            _uiState.update {
                it.copy(
                    participants = participants,
                    groupService = groupService
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse participants", e)
            _uiState.update { it.copy(error = "Failed to load participants") }
        }
    }

    fun updateGroupName(name: String) {
        _uiState.update { it.copy(groupName = name) }
    }

    fun clearGroupName() {
        _uiState.update { it.copy(groupName = "") }
    }

    fun updateGroupPhoto(uri: Uri) {
        _uiState.update { it.copy(groupPhotoUri = uri) }
    }

    fun clearGroupPhoto() {
        _uiState.update { it.copy(groupPhotoUri = null) }
    }

    fun createGroup() {
        viewModelScope.launch {
            val participants = _uiState.value.participants
            if (participants.size < 2) {
                _uiState.update { it.copy(error = "A group needs at least 2 participants") }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }

            try {
                val addresses = participants.map { it.address }
                val groupService = _uiState.value.groupService
                val groupName = _uiState.value.groupName.takeIf { it.isNotBlank() }
                val groupPhotoUri = _uiState.value.groupPhotoUri

                when (groupService) {
                    GroupServiceType.MMS -> {
                        // Create local MMS group chat
                        val chatGuid = "mms;-;${addresses.sorted().joinToString(",")}"

                        val existingChat = chatDao.getChatByGuid(chatGuid)
                        if (existingChat == null) {
                            // Save custom photo if provided
                            val customAvatarPath = groupPhotoUri?.let { uri ->
                                saveGroupPhoto(chatGuid, uri)
                            }

                            val newChat = ChatEntity(
                                guid = chatGuid,
                                chatIdentifier = addresses.joinToString(", "),
                                displayName = groupName,
                                isArchived = false,
                                isPinned = false,
                                isGroup = true,
                                hasUnreadMessage = false,
                                unreadCount = 0,
                                lastMessageDate = System.currentTimeMillis(),
                                lastMessageText = null,
                                customAvatarPath = customAvatarPath
                            )
                            chatDao.insertChat(newChat)
                        } else if (groupName != null || groupPhotoUri != null) {
                            // Update existing chat with name/photo
                            val customAvatarPath = groupPhotoUri?.let { uri ->
                                saveGroupPhoto(chatGuid, uri)
                            }
                            chatDao.updateDisplayName(chatGuid, groupName)
                            customAvatarPath?.let {
                                chatDao.updateCustomAvatarPath(chatGuid, it)
                            }
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                createdChatGuid = chatGuid
                            )
                        }
                    }

                    GroupServiceType.IMESSAGE -> {
                        // Create iMessage group via BlueBubbles server
                        val response = api.createChat(
                            CreateChatRequest(
                                addresses = addresses,
                                service = "iMessage"
                            )
                        )

                        val body = response.body()
                        if (response.isSuccessful && body?.data != null) {
                            val chatGuid = body.data.guid

                            // Update group name if provided
                            if (groupName != null) {
                                try {
                                    api.updateChat(chatGuid, UpdateChatRequest(displayName = groupName))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to set group name", e)
                                }
                            }

                            // Save custom photo locally if provided
                            if (groupPhotoUri != null) {
                                val customAvatarPath = saveGroupPhoto(chatGuid, groupPhotoUri)
                                customAvatarPath?.let {
                                    chatDao.updateCustomAvatarPath(chatGuid, it)
                                }
                            }

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    createdChatGuid = chatGuid
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = body?.message ?: "Failed to create group"
                                )
                            }
                        }
                    }

                    GroupServiceType.UNDETERMINED -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Unable to determine group type"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create group", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create group"
                    )
                }
            }
        }
    }

    /**
     * Save group photo to local storage
     * @return The path to the saved file, or null if failed
     */
    private fun saveGroupPhoto(chatGuid: String, uri: Uri): String? {
        return try {
            val avatarsDir = File(context.filesDir, "group_avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }

            // Sanitize chatGuid for filename
            val sanitizedGuid = chatGuid.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val photoFile = File(avatarsDir, "${sanitizedGuid}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(photoFile).use { output ->
                    input.copyTo(output)
                }
            }

            photoFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save group photo", e)
            null
        }
    }

    fun resetCreatedChatGuid() {
        _uiState.update { it.copy(createdChatGuid = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class GroupSetupUiState(
    val participants: List<GroupParticipant> = emptyList(),
    val groupService: GroupServiceType = GroupServiceType.UNDETERMINED,
    val groupName: String = "",
    val groupPhotoUri: Uri? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val createdChatGuid: String? = null
)
