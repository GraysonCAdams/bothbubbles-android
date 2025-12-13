package com.bothbubbles.ui.preview

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.bothbubbles.ui.theme.BothBubblesTheme

/**
 * Wrapper composable for previews that applies the app theme.
 * Use this in all preview functions to ensure consistent theming.
 *
 * Usage:
 * ```kotlin
 * @Preview
 * @Composable
 * private fun MyComponentPreview() {
 *     PreviewWrapper {
 *         MyComponent()
 *     }
 * }
 * ```
 */
@Composable
fun PreviewWrapper(
    darkTheme: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BothBubblesTheme(darkTheme = darkTheme) {
        Surface(modifier = modifier) {
            content()
        }
    }
}

/**
 * Multi-preview annotation for testing both light and dark themes.
 * Apply this to preview functions to generate both variants automatically.
 *
 * Usage:
 * ```kotlin
 * @ThemePreviews
 * @Composable
 * private fun MyComponentPreview() {
 *     PreviewWrapper {
 *         MyComponent()
 *     }
 * }
 * ```
 */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ThemePreviews

/**
 * Multi-preview annotation for different device configurations.
 * Useful for testing responsive layouts.
 */
@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp,dpi=240", showBackground = true)
annotation class DevicePreviews

/**
 * Multi-preview annotation for different font scales.
 * Useful for accessibility testing.
 */
@Preview(name = "Default", fontScale = 1f, showBackground = true)
@Preview(name = "Large", fontScale = 1.5f, showBackground = true)
@Preview(name = "Largest", fontScale = 2f, showBackground = true)
annotation class FontScalePreviews

/**
 * Comprehensive preview annotation combining theme and font scale variations.
 * Use sparingly as it generates many previews.
 */
@Preview(name = "Light - Default", showBackground = true)
@Preview(name = "Light - Large Font", fontScale = 1.5f, showBackground = true)
@Preview(name = "Dark - Default", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Dark - Large Font", fontScale = 1.5f, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ComprehensivePreviews
