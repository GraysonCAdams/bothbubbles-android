package com.bothbubbles.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bothbubbles.data.local.db.entity.LinkPreviewEntity
import com.bothbubbles.data.local.db.entity.LinkPreviewFetchStatus
import com.bothbubbles.data.repository.LinkPreviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bothbubbles.util.parsing.UrlParsingUtils
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

    companion object {
        private const val MAX_CACHE_SIZE = 50 // Limit cache to 50 URLs per chat session
    }

    // LRU cache using LinkedHashMap with accessOrder=true
    // Automatically evicts oldest entries when capacity is exceeded
    private val _previewCache = object : LinkedHashMap<String, MutableStateFlow<LinkPreviewState>>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<LinkPreviewState>>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

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

    override fun onCleared() {
        super.onCleared()
        _previewCache.clear()
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

/**
 * Borderless link preview composable for use outside message bubbles.
 * Renders without Card container, using only subtle rounded corners.
 */
@Composable
fun BorderlessLinkPreview(
    url: String,
    isFromMe: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 300.dp,
    viewModel: LinkPreviewViewModel = hiltViewModel()
) {
    val previewState by viewModel.getPreviewState(url).collectAsState()
    val domain = remember(url) { UrlParsingUtils.extractDomain(url) }

    when (val state = previewState) {
        is LinkPreviewState.Loading -> {
            BorderlessLinkPreviewShimmer(
                showImage = true,
                maxWidth = maxWidth,
                modifier = modifier
            )
        }

        is LinkPreviewState.Success -> {
            BorderlessLinkPreviewCard(
                preview = state.preview,
                isFromMe = isFromMe,
                maxWidth = maxWidth,
                modifier = modifier
            )
        }

        is LinkPreviewState.Error -> {
            // For errors in borderless mode, show minimal
            BorderlessLinkPreviewMinimal(
                url = url,
                domain = domain,
                maxWidth = maxWidth,
                modifier = modifier
            )
        }

        is LinkPreviewState.NoPreview -> {
            BorderlessLinkPreviewMinimal(
                url = url,
                domain = domain,
                maxWidth = maxWidth,
                modifier = modifier
            )
        }
    }
}
