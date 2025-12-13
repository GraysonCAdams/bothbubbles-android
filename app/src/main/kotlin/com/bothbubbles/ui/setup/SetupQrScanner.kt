package com.bothbubbles.ui.setup

import androidx.compose.runtime.Composable
import com.bothbubbles.ui.components.common.QrCodeScanner

@Composable
internal fun QrScannerOverlay(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Full-screen QR scanner using CameraX + ML Kit
    QrCodeScanner(
        onQrCodeScanned = onQrCodeScanned,
        onDismiss = onDismiss
    )
}
