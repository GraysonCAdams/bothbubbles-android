package com.bothbubbles.ui.media

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bothbubbles.ui.components.common.Avatar
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

    // Handle save result
    LaunchedEffect(uiState.saveResult) {
        uiState.saveResult?.let { result ->
            when (result) {
                is SaveResult.Success -> {
                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                }
                is SaveResult.Error -> {
                    Toast.makeText(context, "Failed to save: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
            viewModel.clearSaveResult()
        }
    }

    // Share action
    val onShare: () -> Unit = {
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
                    key = { index -> uiState.mediaList.getOrNull(index)?.attachment?.guid ?: index }
                ) { page ->
                    val media = uiState.mediaList.getOrNull(page)
                    if (media != null) {
                        MediaPage(
                            attachment = media.attachment,
                            onTap = { showControls = !showControls }
                        )
                    }
                }
            }
        }

        // Top bar with sender info
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            MediaViewerTopBar(
                senderName = uiState.senderName,
                senderAvatarPath = uiState.senderAvatarPath,
                senderAddress = uiState.senderAddress,
                dateMillis = uiState.messageDateMillis,
                fileSize = uiState.attachment?.totalBytes,
                isFromMe = uiState.isFromMe,
                onBack = onNavigateBack
            )
        }

        // Download progress indicator
        if (uiState.isDownloading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { uiState.downloadProgress },
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(uiState.downloadProgress * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Bottom controls (action bar + page indicator)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicator
                if (uiState.hasMultipleMedia) {
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
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Bottom action bar
                MediaActionBar(
                    canSave = uiState.canSave,
                    canShare = uiState.canShare,
                    isSaving = uiState.isSaving,
                    onSave = viewModel::saveToGallery,
                    onShare = onShare,
                    onInfo = viewModel::showInfoDialog
                )
            }
        }
    }

    // Info dialog
    if (uiState.showInfoDialog && uiState.attachment != null) {
        MediaInfoDialog(
            attachment = uiState.attachment!!,
            senderName = uiState.senderName,
            dateMillis = uiState.messageDateMillis,
            onDismiss = viewModel::hideInfoDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewerTopBar(
    senderName: String?,
    senderAvatarPath: String?,
    senderAddress: String?,
    dateMillis: Long?,
    fileSize: Long?,
    isFromMe: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Avatar
                if (senderName != null) {
                    Avatar(
                        name = senderName,
                        avatarPath = senderAvatarPath,
                        size = 36.dp,
                        hasContactInfo = senderAvatarPath != null || isFromMe
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Name and metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = senderName ?: "Unknown",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Date and file size
                    val metadata = buildString {
                        dateMillis?.let { millis ->
                            append(
                                DateUtils.getRelativeDateTimeString(
                                    context,
                                    millis,
                                    DateUtils.MINUTE_IN_MILLIS,
                                    DateUtils.WEEK_IN_MILLIS,
                                    DateUtils.FORMAT_ABBREV_RELATIVE
                                )
                            )
                        }
                        fileSize?.let { size ->
                            if (isNotEmpty()) append(" • ")
                            append(Formatter.formatShortFileSize(context, size))
                        }
                    }

                    if (metadata.isNotEmpty()) {
                        Text(
                            text = metadata,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.5f)
        )
    )
}

@Composable
private fun MediaActionBar(
    canSave: Boolean,
    canShare: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(
                icon = if (isSaving) Icons.Default.HourglassTop else Icons.Default.Download,
                label = if (isSaving) "Saving..." else "Save",
                enabled = canSave,
                onClick = onSave
            )
            ActionButton(
                icon = Icons.Default.Share,
                label = "Share",
                enabled = canShare,
                onClick = onShare
            )
            ActionButton(
                icon = Icons.Outlined.Info,
                label = "Info",
                enabled = true,
                onClick = onInfo
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = alpha)
        )
    }
}
