package com.bothbubbles.ui

import androidx.compose.runtime.compositionLocalOf
import com.bothbubbles.seam.stitches.StitchCapabilities

/**
 * Provides stitch capabilities to composables without prop drilling.
 *
 * Usage:
 * CompositionLocalProvider(LocalStitchCapabilities provides capabilities) {
 *     // Child composables can access via LocalStitchCapabilities.current
 * }
 */
val LocalStitchCapabilities = compositionLocalOf<StitchCapabilities?> { null }
