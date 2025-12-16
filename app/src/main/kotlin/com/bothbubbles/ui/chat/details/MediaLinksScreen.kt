package com.bothbubbles.ui.chat.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLinksScreen(
    chatGuid: String,
    onNavigateBack: () -> Unit,
    onMediaClick: (String) -> Unit,
    onSeeAllImages: () -> Unit,
    onSeeAllVideos: () -> Unit,
    onSeeAllPlaces: () -> Unit = {},
    onSeeAllLinks: () -> Unit = {},
    viewModel: MediaLinksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    title = {
                        // Search bar style
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Search conversation",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Filter chips - navigate to dedicated screens
                FilterChipsRow(
                    selectedFilter = uiState.selectedFilter,
                    onFilterSelected = { filter ->
                        when (filter) {
                            MediaFilter.IMAGES -> onSeeAllImages()
                            MediaFilter.VIDEOS -> onSeeAllVideos()
                            MediaFilter.PLACES -> onSeeAllPlaces()
                            MediaFilter.LINKS -> onSeeAllLinks()
                            MediaFilter.ALL -> {} // Already on this screen
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Images section
                if (uiState.images.isNotEmpty()) {
                    item {
                        MediaSectionHeader(
                            title = "Images",
                            onSeeAllClick = onSeeAllImages
                        )
                    }
                    item {
                        ImagesRow(
                            images = uiState.images,
                            onImageClick = onMediaClick
                        )
                    }
                }

                // Videos section
                if (uiState.videos.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        MediaSectionHeader(
                            title = "Videos",
                            onSeeAllClick = onSeeAllVideos
                        )
                    }
                    item {
                        VideosRow(
                            videos = uiState.videos,
                            onVideoClick = onMediaClick
                        )
                    }
                }

                // Places section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    MediaSectionHeader(
                        title = "Places",
                        onSeeAllClick = onSeeAllPlaces
                    )
                }

                // Links section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    MediaSectionHeader(
                        title = "Links",
                        onSeeAllClick = onSeeAllLinks
                    )
                }

                // Empty state for links or show link previews
                if (uiState.links.isEmpty()) {
                    item {
                        Text(
                            text = "No links shared yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(uiState.links) { link ->
                        LinkPreviewCard(
                            link = link,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
