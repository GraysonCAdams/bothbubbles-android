package com.bothbubbles.ui.chat.composer.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bothbubbles.ui.chat.composer.animations.ComposerMotionTokens

/**
 * Represents a GIF item from search results.
 */
data class GifItem(
    val id: String,
    val previewUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int
)

/**
 * State for the GIF picker panel.
 */
sealed class GifPickerState {
    data object Idle : GifPickerState()
    data object Loading : GifPickerState()
    data class Success(val gifs: List<GifItem>) : GifPickerState()
    data class Error(val message: String) : GifPickerState()
}

/**
 * GIF picker panel with search functionality.
 *
 * Features:
 * - Search bar with auto-focus when opened
 * - Grid of GIF results with staggered layout
 * - Loading and error states
 * - Trending GIFs shown initially
 * - Scroll triggers keyboard dismiss
 *
 * Note: Integration with Giphy/Tenor API should be done in the ViewModel.
 * Visibility, animations, and drag-to-dismiss are handled by ComposerPanelHost.
 *
 * @param visible Whether the panel is visible (kept for compatibility, handled by parent)
 * @param state Current state of the GIF picker
 * @param searchQuery Current search query
 * @param onSearchQueryChange Callback when search query changes
 * @param onSearch Callback when search is submitted
 * @param onGifSelected Callback when a GIF is selected
 * @param onDismiss Callback when panel should be dismissed
 * @param modifier Modifier for the panel
 */
@Composable
fun GifPickerPanel(
    visible: Boolean,
    state: GifPickerState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onGifSelected: (GifItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Use fixed expanded height to avoid animation race with imePadding
    // When panel opens, keyboard is hidden anyway. Using dynamic height based on keyboard
    // visibility caused jarring shift-down-then-up animation.
    val contentHeight = ComposerMotionTokens.Dimension.PanelHeight +
        ComposerMotionTokens.Dimension.PanelExpandedExtra

    // Auto-focus search field when panel becomes visible
    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    // Panel content - visibility/animations/drag handled by ComposerPanelHost
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp) // Match spacing with bottom (12dp spacer below search bar)
        ) {
            // Search bar
            GifSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onSearch = {
                    onSearch(searchQuery)
                    focusManager.clearFocus()
                },
                onClear = { onSearchQueryChange("") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Content based on state - fixed expanded height for consistent UX
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeight),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is GifPickerState.Idle -> {
                        Text(
                            text = "Search for GIFs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is GifPickerState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is GifPickerState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Failed to load GIFs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is GifPickerState.Success -> {
                        if (state.gifs.isEmpty()) {
                            Text(
                                text = "No GIFs found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            GifGrid(
                                gifs = state.gifs,
                                onGifSelected = { gif ->
                                    onGifSelected(gif)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Search bar for GIF search.
 */
@Composable
private fun GifSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(52.dp),
        placeholder = {
            Text(
                text = "Search GIFs",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onSearch() }
        ),
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

/**
 * Grid of GIF items.
 */
@Composable
private fun GifGrid(
    gifs: List<GifItem>,
    onGifSelected: (GifItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Hide keyboard when user scrolls ~100dp worth
    LaunchedEffect(gridState) {
        var previousScrollOffset = gridState.firstVisibleItemScrollOffset
        var previousFirstVisibleItem = gridState.firstVisibleItemIndex
        var accumulatedScroll = 0

        snapshotFlow {
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                gridState.isScrollInProgress
            )
        }.collect { (currentIndex, currentOffset, isScrolling) ->
            if (isScrolling) {
                val scrollDelta = if (currentIndex == previousFirstVisibleItem) {
                    currentOffset - previousScrollOffset
                } else {
                    (currentIndex - previousFirstVisibleItem) * 200
                }
                accumulatedScroll += kotlin.math.abs(scrollDelta)

                // Hide keyboard after scrolling threshold
                if (accumulatedScroll > ComposerMotionTokens.Dimension.GifScrollThresholdPx) {
                    keyboardController?.hide()
                    accumulatedScroll = 0
                }
            } else {
                accumulatedScroll = 0
            }

            previousFirstVisibleItem = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = gifs,
            key = { it.id }
        ) { gif ->
            GifGridItem(
                gif = gif,
                onClick = { onGifSelected(gif) }
            )
        }
    }
}

/**
 * Individual GIF item in the grid.
 */
@Composable
private fun GifGridItem(
    gif: GifItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Calculate aspect ratio, default to 1:1 if dimensions are invalid
    val aspectRatio = if (gif.width > 0 && gif.height > 0) {
        gif.width.toFloat() / gif.height.toFloat()
    } else {
        1f
    }.coerceIn(0.5f, 2f) // Clamp to reasonable range

    Surface(
        modifier = modifier
            .aspectRatio(1f) // Force square in grid
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        AsyncImage(
            model = gif.previewUrl,
            contentDescription = "GIF",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Simple GIF picker panel with placeholder content.
 * Use this version when GIF API is not yet integrated.
 */
@Composable
fun GifPickerPanelSimple(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    GifPickerPanel(
        visible = visible,
        state = GifPickerState.Idle,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onSearch = { /* TODO: Implement Giphy/Tenor search */ },
        onGifSelected = { /* TODO: Handle GIF selection */ },
        onDismiss = onDismiss,
        modifier = modifier
    )
}
