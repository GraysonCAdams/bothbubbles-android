package com.bothbubbles.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// iMessage blue for sent messages
private val IMessageBlue = Color(0xFF007AFF)
private val IMessageBlueDark = Color(0xFF0A84FF)

// SMS green for SMS messages
private val SmsGreen = Color(0xFF34C759)
private val SmsGreenDark = Color(0xFF30D158)

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

@Composable
fun BothBubblesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

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
object BothBubblesTheme {
    val bubbleColors: BubbleColors
        @Composable
        get() = LocalBubbleColors.current
}
