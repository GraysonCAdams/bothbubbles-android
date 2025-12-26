package com.bothbubbles.seam.hems.autoresponder

/**
 * A quick-add example for auto-responder rules provided by a Stitch.
 *
 * This allows Stitches to suggest pre-configured auto-responder rules
 * that users can quickly add with one tap.
 *
 * @property name Display name for the example (e.g., "iMessage Auto-Reply")
 * @property message The auto-response message to send
 * @property description Brief description of what this rule does
 */
data class AutoResponderQuickAddExample(
    val name: String,
    val message: String,
    val description: String
)
