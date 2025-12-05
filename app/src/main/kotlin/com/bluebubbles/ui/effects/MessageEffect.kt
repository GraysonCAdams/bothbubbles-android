package com.bluebubbles.ui.effects

/**
 * Represents iMessage expressive send effects.
 * Maps to Apple's expressiveSendStyleId field.
 */
sealed class MessageEffect {
    abstract val appleId: String
    abstract val displayName: String

    /**
     * Bubble effects - applied to individual message bubbles
     */
    sealed class Bubble : MessageEffect() {
        data object Slam : Bubble() {
            override val appleId = "com.apple.MobileSMS.expressivesend.impact"
            override val displayName = "Slam"
        }

        data object Loud : Bubble() {
            override val appleId = "com.apple.MobileSMS.expressivesend.loud"
            override val displayName = "Loud"
        }

        data object Gentle : Bubble() {
            override val appleId = "com.apple.MobileSMS.expressivesend.gentle"
            override val displayName = "Gentle"
        }

        data object InvisibleInk : Bubble() {
            override val appleId = "com.apple.MobileSMS.expressivesend.invisibleink"
            override val displayName = "Invisible Ink"
        }
    }

    /**
     * Screen effects - full-screen overlay animations
     */
    sealed class Screen : MessageEffect() {
        data object Echo : Screen() {
            override val appleId = "com.apple.messages.effect.CKEchoEffect"
            override val displayName = "Echo"
        }

        data object Spotlight : Screen() {
            override val appleId = "com.apple.messages.effect.CKSpotlightEffect"
            override val displayName = "Spotlight"
        }

        data object Balloons : Screen() {
            override val appleId = "com.apple.messages.effect.CKHappyBirthdayEffect"
            override val displayName = "Balloons"
        }

        data object Confetti : Screen() {
            override val appleId = "com.apple.messages.effect.CKConfettiEffect"
            override val displayName = "Confetti"
        }

        data object Hearts : Screen() {
            override val appleId = "com.apple.messages.effect.CKHeartEffect"
            override val displayName = "Hearts"
        }

        data object Lasers : Screen() {
            override val appleId = "com.apple.messages.effect.CKLasersEffect"
            override val displayName = "Lasers"
        }

        data object Fireworks : Screen() {
            override val appleId = "com.apple.messages.effect.CKFireworksEffect"
            override val displayName = "Fireworks"
        }

        data object Celebration : Screen() {
            override val appleId = "com.apple.messages.effect.CKSparklesEffect"
            override val displayName = "Celebration"
        }
    }

    companion object {
        /**
         * All bubble effects for iteration (e.g., in effect picker UI)
         */
        val allBubbleEffects: List<Bubble> = listOf(
            Bubble.Slam,
            Bubble.Loud,
            Bubble.Gentle,
            Bubble.InvisibleInk
        )

        /**
         * All screen effects for iteration
         */
        val allScreenEffects: List<Screen> = listOf(
            Screen.Echo,
            Screen.Spotlight,
            Screen.Balloons,
            Screen.Confetti,
            Screen.Hearts,
            Screen.Lasers,
            Screen.Fireworks,
            Screen.Celebration
        )

        /**
         * Parse an Apple expressiveSendStyleId to a MessageEffect
         */
        fun fromStyleId(styleId: String?): MessageEffect? {
            if (styleId.isNullOrBlank()) return null

            return when {
                // Bubble effects
                styleId.contains("expressivesend.impact", ignoreCase = true) -> Bubble.Slam
                styleId.contains("expressivesend.loud", ignoreCase = true) -> Bubble.Loud
                styleId.contains("expressivesend.gentle", ignoreCase = true) -> Bubble.Gentle
                styleId.contains("expressivesend.invisibleink", ignoreCase = true) -> Bubble.InvisibleInk

                // Screen effects
                styleId.contains("CKEchoEffect", ignoreCase = true) -> Screen.Echo
                styleId.contains("CKSpotlightEffect", ignoreCase = true) -> Screen.Spotlight
                styleId.contains("CKHappyBirthdayEffect", ignoreCase = true) -> Screen.Balloons
                styleId.contains("CKConfettiEffect", ignoreCase = true) -> Screen.Confetti
                styleId.contains("CKHeartEffect", ignoreCase = true) -> Screen.Hearts
                styleId.contains("CKLasersEffect", ignoreCase = true) -> Screen.Lasers
                styleId.contains("CKFireworksEffect", ignoreCase = true) -> Screen.Fireworks
                styleId.contains("CKSparklesEffect", ignoreCase = true) -> Screen.Celebration

                else -> null
            }
        }

        /**
         * Check if a styleId represents a bubble effect
         */
        fun isBubbleEffect(styleId: String?): Boolean {
            return fromStyleId(styleId) is Bubble
        }

        /**
         * Check if a styleId represents a screen effect
         */
        fun isScreenEffect(styleId: String?): Boolean {
            return fromStyleId(styleId) is Screen
        }
    }
}
