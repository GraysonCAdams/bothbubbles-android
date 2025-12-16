package com.bothbubbles.ui.media

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    onNavigateBack: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Controls visibility state
    var showControls by remember { mutableStateOf(true) }

    // Pager state for swiping between media
    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.mediaCount.coerceAtLeast(1) }
    )

    // Sync pager with ViewModel when page changes
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != uiState.currentIndex && uiState.mediaList.isNotEmpty()) {
            viewModel.onPageChanged(pagerState.currentPage)
        }
    }

    // Scroll to initial page when media list loads
    LaunchedEffect(uiState.currentIndex, uiState.mediaList.size) {
        if (uiState.mediaList.isNotEmpty() && pagerState.currentPage != uiState.currentIndex) {
            pagerState.scrollToPage(uiState.currentIndex)
        }
    }

    // Error handling
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            // Could show snackbar here
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main content
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            uiState.mediaList.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Media not available",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { index -> uiState.mediaList.getOrNull(index)?.guid ?: index }
                ) { page ->
                    val attachment = uiState.mediaList.getOrNull(page)
                    if (attachment != null) {
                        MediaPage(
                            attachment = attachment,
                            onTap = { showControls = !showControls }
                        )
                    }
                }
            }
        }

        // Top bar with controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Text(
                        text = uiState.title,
                        color = Color.White,
                        maxLines = 1
                    )
                },
                actions = {
                    // Share button
                    IconButton(
                        onClick = {
                            uiState.attachment?.localPath?.let { path ->
                                val file = File(path)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = uiState.attachment?.mimeType ?: "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share"))
                            }
                        },
                        enabled = uiState.attachment?.localPath != null
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            )
        }

        // Page indicator at bottom
        if (uiState.hasMultipleMedia) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.mediaCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
