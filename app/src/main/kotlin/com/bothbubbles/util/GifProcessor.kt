package com.bothbubbles.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for processing GIF files to fix common issues.
 */
object GifProcessor {

    /**
     * Fixes GIFs with zero-delay frames by setting a minimum 10 centisecond (100ms) delay.
     *
     * Some GIFs have frames with 0 delay which causes them to play extremely fast
     * (as fast as the system can render). This scans for GIF Graphics Control Extension
     * blocks and sets a minimum delay for any frames that have zero delay.
     *
     * GIF Graphics Control Extension format:
     * - Byte 0: Extension Introducer (0x21)
     * - Byte 1: Graphic Control Label (0xF9)
     * - Byte 2: Block Size (0x04)
     * - Byte 3: Packed byte (disposal method, user input flag, transparency flag)
     * - Bytes 4-5: Delay time in centiseconds (little-endian)
     * - Byte 6: Transparent color index
     * - Byte 7: Block Terminator (0x00)
     *
     * @param bytes The raw GIF file bytes
     * @return The fixed GIF bytes with minimum frame delays applied
     */
    suspend fun fixSpeedyGif(bytes: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        // Don't process if too small to be a valid GIF
        if (bytes.size < 10) return@withContext bytes

        // Verify GIF header (GIF87a or GIF89a)
        val header = String(bytes.sliceArray(0..5))
        if (header != "GIF87a" && header != "GIF89a") {
            return@withContext bytes
        }

        val result = bytes.copyOf()
        var modified = false

        // Scan for Graphics Control Extension blocks
        // Pattern: 0x21 (extension), 0xF9 (graphic control), 0x04 (block size)
        var i = 0
        while (i < result.size - 7) {
            if (result[i] == 0x21.toByte() &&
                result[i + 1] == 0xF9.toByte() &&
                result[i + 2] == 0x04.toByte()
            ) {
                // Found a Graphics Control Extension block
                // Delay is at offset +4 and +5 (little-endian, in centiseconds)
                val delayLow = result[i + 4].toInt() and 0xFF
                val delayHigh = result[i + 5].toInt() and 0xFF
                val delay = delayLow or (delayHigh shl 8)

                // If delay is 0 or very small (< 2 centiseconds = 20ms), set to 10 (100ms)
                if (delay < 2) {
                    result[i + 4] = 0x0A.toByte() // 10 centiseconds = 100ms
                    result[i + 5] = 0x00.toByte()
                    modified = true
                }

                // Skip past this block
                i += 8
            } else {
                i++
            }
        }

        if (modified) {
            result
        } else {
            bytes // Return original if no modifications needed
        }
    }

    /**
     * Checks if the given bytes represent a GIF file.
     */
    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val header = String(bytes.sliceArray(0..5))
        return header == "GIF87a" || header == "GIF89a"
    }
}
