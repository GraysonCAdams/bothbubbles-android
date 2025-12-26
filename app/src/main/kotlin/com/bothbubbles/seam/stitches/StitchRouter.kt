package com.bothbubbles.seam.stitches

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes messages to the appropriate Stitch based on chat GUID and capabilities.
 * Full implementation comes in Stage 5.
 */
@Singleton
class StitchRouter @Inject constructor(
    private val registry: StitchRegistry
) {
    fun getStitchForChat(chatGuid: String): Stitch? = registry.getStitchForChat(chatGuid)
}
