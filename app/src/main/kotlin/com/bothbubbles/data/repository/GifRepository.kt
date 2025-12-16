package com.bothbubbles.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import com.bothbubbles.core.network.api.TenorApi
import com.bothbubbles.core.network.api.TenorGif
import com.bothbubbles.ui.chat.composer.panels.GifItem
import com.bothbubbles.ui.chat.composer.panels.GifPickerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GifRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tenorApi: TenorApi
) {
    companion object {
        // TODO: Move to BuildConfig or secure storage
        private const val TENOR_API_KEY = "AIzaSyAyimkuYQYF_FXVALexPuGQctUWRURdCYQ"
    }

    private val _state = MutableStateFlow<GifPickerState>(GifPickerState.Idle)
    val state: StateFlow<GifPickerState> = _state.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var nextPosition: String? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    suspend fun loadFeatured() {
        _state.value = GifPickerState.Loading
        try {
            val response = tenorApi.getFeatured(
                apiKey = TENOR_API_KEY,
                limit = 20
            )
            nextPosition = response.next
            _state.value = GifPickerState.Success(response.results.mapToGifItems())
        } catch (e: Exception) {
            Timber.e(e, "Error loading featured GIFs")
            _state.value = GifPickerState.Error("Failed to load GIFs")
        }
    }

    suspend fun search(query: String) {
        if (query.isBlank()) {
            loadFeatured()
            return
        }

        _state.value = GifPickerState.Loading
        try {
            val response = tenorApi.search(
                query = query,
                apiKey = TENOR_API_KEY,
                limit = 20
            )
            nextPosition = response.next
            _state.value = GifPickerState.Success(response.results.mapToGifItems())
        } catch (e: Exception) {
            Timber.e(e, "Error searching GIFs")
            _state.value = GifPickerState.Error("Search failed")
        }
    }

    suspend fun loadMore() {
        val currentState = _state.value
        if (currentState !is GifPickerState.Success || nextPosition == null) return

        try {
            val response = if (_searchQuery.value.isBlank()) {
                tenorApi.getFeatured(
                    apiKey = TENOR_API_KEY,
                    limit = 20,
                    position = nextPosition
                )
            } else {
                tenorApi.search(
                    query = _searchQuery.value,
                    apiKey = TENOR_API_KEY,
                    limit = 20,
                    position = nextPosition
                )
            }
            nextPosition = response.next
            _state.value = GifPickerState.Success(
                currentState.gifs + response.results.mapToGifItems()
            )
        } catch (e: Exception) {
            Timber.e(e, "Error loading more GIFs")
        }
    }

    /**
     * Downloads a GIF to cache and returns the local file URI.
     */
    suspend fun downloadGif(gif: GifItem): Uri? = withContext(Dispatchers.IO) {
        try {
            val gifDir = File(context.cacheDir, "gifs")
            if (!gifDir.exists()) gifDir.mkdirs()

            val fileName = "${gif.id}.gif"
            val gifFile = File(gifDir, fileName)

            URL(gif.fullUrl).openStream().use { input ->
                gifFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                gifFile
            )
        } catch (e: Exception) {
            Timber.e(e, "Error downloading GIF")
            null
        }
    }

    private fun List<TenorGif>.mapToGifItems(): List<GifItem> {
        return mapNotNull { gif ->
            val preview = gif.media_formats["tinygif"] ?: gif.media_formats["gif"]
            val full = gif.media_formats["gif"]

            if (preview != null && full != null) {
                GifItem(
                    id = gif.id,
                    previewUrl = preview.url,
                    fullUrl = full.url,
                    width = full.dims?.getOrNull(0) ?: 200,
                    height = full.dims?.getOrNull(1) ?: 200
                )
            } else null
        }
    }
}
