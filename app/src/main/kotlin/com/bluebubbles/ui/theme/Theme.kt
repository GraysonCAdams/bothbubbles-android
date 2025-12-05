package com.bluebubbles.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalContext

// iMessage blue for sent messages
private val IMessageBlue = Color(0xFF007AFF)
private val IMessageBlueDark = Color(0xFF0A84FF)

// SMS green for SMS messages
private val SmsGreen = Color(0xFF34C759)
private val SmsGreenDark = Color(0xFF30D158)

// Default color schemes (fallback when Dynamic Color unavailable)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF1C1C1C),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE7E7E7),
    onSurfaceVariant = Color(0xFF444444),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFFC4C4C4),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFC4C4C4),
    onSecondary = Color(0xFF2E2E2E),
    secondaryContainer = Color(0xFF454545),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFFE6C453),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF1F1F1F),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444444),
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceContainerHighest = Color(0xFF353535),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFF444444),
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

@Composable
fun BlueBubblesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Bubble colors that harmonize with the current theme (Google Messages style)
    val bubbleColors = if (darkTheme) {
        BubbleColors(
            iMessageSent = IMessageBlueDark,
            iMessageSentText = Color.White,
            smsSent = Color(0xFF3D4043), // Charcoal gray for sent SMS/MMS in dark mode (Google Messages)
            smsSentText = Color.White,
            received = Color(0xFF303134), // Dark gray for received bubbles in dark mode
            receivedText = Color.White,
            // Dark mode input colors (Google Messages style)
            inputBackground = Color(0xFF1F1F1F), // Dark surface
            inputFieldBackground = Color(0xFF3C4043), // Medium gray pill
            inputPlaceholder = Color(0xFF9AA0A6), // Muted gray
            inputText = Color.White,
            inputIcon = Color(0xFF9AA0A6), // Muted gray icons
        )
    } else {
        BubbleColors(
            iMessageSent = IMessageBlue,
            iMessageSentText = Color.White,
            smsSent = Color(0xFFE8EAED), // Light gray for sent SMS/MMS in light mode
            smsSentText = Color(0xFF202124), // Dark text
            received = Color(0xFFF1F3F4), // Lighter gray for received bubbles in light mode
            receivedText = Color(0xFF202124), // Dark text
            // Light mode input colors (Google Messages style)
            inputBackground = Color(0xFFF8F9FA), // Light surface
            inputFieldBackground = Color(0xFFE8EAED), // Light gray pill
            inputPlaceholder = Color(0xFF5F6368), // Medium gray
            inputText = Color(0xFF202124), // Dark text
            inputIcon = Color(0xFF5F6368), // Medium gray icons
        )
    }

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
object BlueBubblesTheme {
    val bubbleColors: BubbleColors
        @Composable
        get() = LocalBubbleColors.current
}
