package com.bluebubbles.ui.chat.details

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.bluebubbles.data.local.db.entity.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    val uiState by viewModel.uiState.collectAsState()
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
                        Text(
                            text = "Videos",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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

@Composable
private fun FilterChipsRow(
    selectedFilter: MediaFilter,
    onFilterSelected: (MediaFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipItem(
            icon = Icons.Outlined.Image,
            label = "Images",
            selected = selectedFilter == MediaFilter.IMAGES,
            onClick = { onFilterSelected(MediaFilter.IMAGES) }
        )
        FilterChipItem(
            icon = Icons.Outlined.Videocam,
            label = "Videos",
            selected = selectedFilter == MediaFilter.VIDEOS,
            onClick = { onFilterSelected(MediaFilter.VIDEOS) }
        )
        FilterChipItem(
            icon = Icons.Outlined.Place,
            label = "Places",
            selected = selectedFilter == MediaFilter.PLACES,
            onClick = { onFilterSelected(MediaFilter.PLACES) }
        )
        FilterChipItem(
            icon = Icons.Outlined.Link,
            label = "Links",
            selected = selectedFilter == MediaFilter.LINKS,
            onClick = { onFilterSelected(MediaFilter.LINKS) }
        )
    }
}

@Composable
private fun FilterChipItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

@Composable
private fun MediaSectionHeader(
    title: String,
    onSeeAllClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onSeeAllClick) {
            Text("See all")
        }
    }
}

@Composable
private fun ImagesRow(
    images: List<AttachmentEntity>,
    onImageClick: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            items = images,
            key = { it.guid }
        ) { image ->
            MediaThumbnail(
                attachment = image,
                onClick = { onImageClick(image.guid) },
                showDuration = false
            )
        }
    }
}

@Composable
private fun VideosRow(
    videos: List<AttachmentEntity>,
    onVideoClick: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = videos,
            key = { it.guid }
        ) { video ->
            MediaThumbnail(
                attachment = video,
                onClick = { onVideoClick(video.guid) },
                showDuration = true,
                size = 120
            )
        }
    }
}

@Composable
private fun MediaThumbnail(
    attachment: AttachmentEntity,
    onClick: () -> Unit,
    showDuration: Boolean,
    size: Int = 100
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    var videoDuration by remember { mutableStateOf<Long?>(null) }

    // Get video duration if this is a video and has a local path
    LaunchedEffect(attachment.guid, attachment.localPath) {
        if (showDuration && attachment.isVideo && attachment.localPath != null) {
            videoDuration = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    val file = File(attachment.localPath)
                    if (file.exists()) {
                        retriever.setDataSource(context, Uri.fromFile(file))
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        retriever.release()
                        durationStr?.toLongOrNull()
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val imageUrl = attachment.localPath ?: attachment.webUrl

        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.transferName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    isLoading = state is AsyncImagePainter.State.Loading
                    isError = state is AsyncImagePainter.State.Error
                }
            )
        } else {
            isError = true
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }

        if (isError) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        // Video overlay with play button and duration
        if (showDuration && attachment.isVideo && !isError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )

            // Play button
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Duration badge
            if (videoDuration != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                ) {
                    Text(
                        text = formatVideoDuration(videoDuration!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Formats video duration in milliseconds to a human-readable string (e.g., "1:23" or "1:23:45")
 */
private fun formatVideoDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun LinkPreviewCard(
    link: LinkPreview,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = link.domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${link.senderName} · ${link.timestamp}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
