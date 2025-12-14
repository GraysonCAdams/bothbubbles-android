package com.bothbubbles.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.MaterialColors

// iMessage blue for sent messages
private val IMessageBlue = Color(0xFF007AFF)
private val IMessageBlueDark = Color(0xFF0A84FF)

// SMS green for SMS messages
private val SmsGreen = Color(0xFF34C759)
private val SmsGreenDark = Color(0xFF30D158)

/**
 * Harmonizes a brand color with the theme's primary color.
 * This shifts the hue slightly toward the primary color, making brand colors
 * feel more integrated with the user's dynamic color theme.
 */
private fun harmonizeColor(brandColor: Color, primaryColor: Color): Color {
    val harmonizedArgb = MaterialColors.harmonize(brandColor.toArgb(), primaryColor.toArgb())
    return Color(harmonizedArgb)
}

// MD3 Baseline Light Color Scheme
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

// MD3 Baseline Dark Color Scheme
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
)

/**
 * Custom colors for message bubbles and input area that extend the Material theme
 */
@Immutable
data class BubbleColors(
    val iMessageSent: Color,
    val iMessageSentText: Color,
    val smsSent: Color,
    val smsSentText: Color,
    val received: Color,
    val receivedText: Color,
    // Input field colors (Google Messages style)
    val inputBackground: Color,
    val inputFieldBackground: Color,
    val inputPlaceholder: Color,
    val inputText: Color,
    val inputIcon: Color,
)

val LocalBubbleColors = staticCompositionLocalOf {
    BubbleColors(
        iMessageSent = IMessageBlue,
        iMessageSentText = Color.White,
        smsSent = SmsGreen,
        smsSentText = Color.White,
        received = Color(0xFFE3E3E3),
        receivedText = Color(0xFF1F1F1F),
        inputBackground = Color(0xFF1F1F1F),
        inputFieldBackground = Color(0xFF3C4043),
        inputPlaceholder = Color(0xFF9AA0A6),
        inputText = Color.White,
        inputIcon = Color(0xFF9AA0A6),
    )
}

/**
 * Creates bubble colors that integrate with the given color scheme.
 * Uses semantic container colors for received messages and optionally
 * harmonizes brand colors (iMessage blue, SMS green) with the theme.
 */
@Composable
private fun createBubbleColors(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    harmonizeBrandColors: Boolean
): BubbleColors {
    // Optionally harmonize brand colors with the theme's primary color
    val iMessageColor = if (harmonizeBrandColors) {
        harmonizeColor(
            if (darkTheme) IMessageBlueDark else IMessageBlue,
            colorScheme.primary
        )
    } else {
        if (darkTheme) IMessageBlueDark else IMessageBlue
    }

    val smsColor = if (harmonizeBrandColors) {
        harmonizeColor(
            if (darkTheme) SmsGreenDark else SmsGreen,
            colorScheme.primary
        )
    } else {
        if (darkTheme) SmsGreenDark else SmsGreen
    }

    return if (darkTheme) {
        BubbleColors(
            iMessageSent = iMessageColor,
            iMessageSentText = Color.White,
            smsSent = smsColor,
            smsSentText = Color.White,
            // Use semantic container color for received messages - tinted with theme
            received = colorScheme.surfaceContainerHigh,
            receivedText = colorScheme.onSurface,
            // Dark mode input colors using semantic colors
            inputBackground = colorScheme.surface,
            inputFieldBackground = colorScheme.surfaceContainerHighest,
            inputPlaceholder = colorScheme.onSurfaceVariant,
            inputText = colorScheme.onSurface,
            inputIcon = colorScheme.onSurfaceVariant,
        )
    } else {
        BubbleColors(
            iMessageSent = iMessageColor,
            iMessageSentText = Color.White,
            smsSent = smsColor,
            smsSentText = Color(0xFF202124), // Dark text on green background
            // Use semantic container color for received messages - tinted with theme
            received = colorScheme.surfaceContainerHigh,
            receivedText = colorScheme.onSurface,
            // Light mode input colors using semantic colors
            inputBackground = colorScheme.surfaceContainer,
            inputFieldBackground = colorScheme.surfaceContainerHighest,
            inputPlaceholder = colorScheme.onSurfaceVariant,
            inputText = colorScheme.onSurface,
            inputIcon = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BothBubblesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Use dynamic color on Android 12+ when enabled, otherwise fall back to baseline
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Create bubble colors using semantic colors from the scheme
    // Harmonize brand colors when using dynamic color for better integration
    val bubbleColors = createBubbleColors(
        colorScheme = colorScheme,
        darkTheme = darkTheme,
        harmonizeBrandColors = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    )

    CompositionLocalProvider(LocalBubbleColors provides bubbleColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

/**
 * Extension to access bubble colors from MaterialTheme
 */
object BothBubblesTheme {
    val bubbleColors: BubbleColors
        @Composable
        get() = LocalBubbleColors.current
}
