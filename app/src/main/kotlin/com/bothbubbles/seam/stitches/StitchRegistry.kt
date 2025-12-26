package com.bothbubbles.seam.stitches

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StitchRegistry @Inject constructor(
    private val stitches: Set<@JvmSuppressWildcards Stitch>
) {
    fun getEnabledStitches(): List<Stitch> =
        stitches.filter { it.isEnabled.value }

    fun canDisableStitch(stitch: Stitch): Boolean {
        val enabledCount = getEnabledStitches().size
        return enabledCount > 1 || !stitch.isEnabled.value
    }

    fun getStitchForChat(chatGuid: String): Stitch? {
        return stitches.find { stitch ->
            stitch.chatGuidPrefix?.let { chatGuid.startsWith(it) } ?: false
        }
    }

    fun getStitchById(id: String): Stitch? = stitches.find { it.id == id }

    fun getAllStitches(): Set<Stitch> = stitches
}
