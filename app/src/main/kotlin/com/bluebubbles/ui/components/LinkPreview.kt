package com.bluebubbles.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebubbles.data.local.db.entity.LinkPreviewEntity
import com.bluebubbles.data.local.db.entity.LinkPreviewFetchStatus
import com.bluebubbles.data.repository.LinkPreviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing link preview state.
 * Each message with a URL gets its own instance via the key parameter.
 */
@HiltViewModel
class LinkPreviewViewModel @Inject constructor(
    private val linkPreviewRepository: LinkPreviewRepository
) : ViewModel() {

    private val _previewCache = mutableMapOf<String, MutableStateFlow<LinkPreviewState>>()

    /**
     * Gets the preview state for a URL
     */
    fun getPreviewState(url: String): StateFlow<LinkPreviewState> {
        return _previewCache.getOrPut(url) {
            MutableStateFlow(LinkPreviewState.Loading)
        }.also { stateFlow ->
            if (stateFlow.value == LinkPreviewState.Loading) {
                fetchPreview(url)
            }
        }.asStateFlow()
    }

    /**
     * Fetches the preview for a URL
     */
    private fun fetchPreview(url: String) {
        viewModelScope.launch {
            try {
                // Start observing the preview
                linkPreviewRepository.observeLinkPreview(url).collect { preview ->
                    val state = when {
                        preview == null -> LinkPreviewState.Loading
                        preview.fetchStatus == LinkPreviewFetchStatus.LOADING.name -> LinkPreviewState.Loading
                        preview.fetchStatus == LinkPreviewFetchStatus.SUCCESS.name -> LinkPreviewState.Success(preview)
                        preview.fetchStatus == LinkPreviewFetchStatus.FAILED.name -> LinkPreviewState.Error(preview)
                        preview.fetchStatus == LinkPreviewFetchStatus.NO_PREVIEW.name -> LinkPreviewState.NoPreview
                        else -> LinkPreviewState.Loading
                    }
                    _previewCache[url]?.value = state
                }
            } catch (e: Exception) {
                _previewCache[url]?.value = LinkPreviewState.Error(null)
            }
        }
    }

    /**
     * Retry fetching a failed preview
     */
    fun retryPreview(url: String) {
        _previewCache[url]?.value = LinkPreviewState.Loading
        viewModelScope.launch {
            linkPreviewRepository.refreshLinkPreview(url)
        }
    }
}

/**
 * State for a link preview
 */
sealed class LinkPreviewState {
    data object Loading : LinkPreviewState()
    data class Success(val preview: LinkPreviewEntity) : LinkPreviewState()
    data class Error(val preview: LinkPreviewEntity?) : LinkPreviewState()
    data object NoPreview : LinkPreviewState()
}

/**
 * Composable that displays a link preview for a URL.
 * Automatically fetches the preview metadata and displays the appropriate state.
 */
@Composable
fun LinkPreview(
    url: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    viewModel: LinkPreviewViewModel = hiltViewModel()
) {
    val previewState by viewModel.getPreviewState(url).collectAsState()
    val domain = remember(url) { UrlParsingUtils.extractDomain(url) }

    when (val state = previewState) {
        is LinkPreviewState.Loading -> {
            LinkPreviewShimmer(
                isFromMe = isFromMe,
                showImage = true,
                modifier = modifier
            )
        }

        is LinkPreviewState.Success -> {
            LinkPreviewCard(
                preview = state.preview,
                isFromMe = isFromMe,
                modifier = modifier
            )
        }

        is LinkPreviewState.Error -> {
            LinkPreviewError(
                url = url,
                domain = domain,
                isFromMe = isFromMe,
                onRetry = { viewModel.retryPreview(url) },
                modifier = modifier
            )
        }

        is LinkPreviewState.NoPreview -> {
            // Show minimal preview with just the domain
            LinkPreviewMinimal(
                url = url,
                domain = domain,
                isFromMe = isFromMe,
                modifier = modifier
            )
        }
    }
}
