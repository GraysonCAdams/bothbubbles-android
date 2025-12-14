package com.bothbubbles.ui.chat.paging

import java.util.BitSet

/**
 * Helper functions for message paging operations.
 * These are stateless utility functions extracted from MessagePagingController.
 */
object MessagePagingHelpers {

    /**
     * Find gaps (unloaded ranges) in a given range.
     *
     * @param start Start position (inclusive)
     * @param end End position (exclusive)
     * @param loadStatus BitSet tracking loaded positions
     * @return List of ranges that need to be loaded
     */
    fun findGaps(start: Int, end: Int, loadStatus: BitSet): List<IntRange> {
        val gaps = mutableListOf<IntRange>()
        var gapStart: Int? = null

        for (i in start until end) {
            val isLoaded = loadStatus.get(i)

            if (!isLoaded && gapStart == null) {
                gapStart = i
            } else if (isLoaded && gapStart != null) {
                gaps.add(gapStart until i)
                gapStart = null
            }
        }

        // Handle gap that extends to end
        if (gapStart != null) {
            gaps.add(gapStart until end)
        }

        return gaps
    }

    /**
     * Compute loaded ranges from BitSet.
     *
     * @param loadStatus BitSet tracking loaded positions
     * @param totalSize Total size of the list
     * @return List of loaded ranges
     */
    fun computeLoadedRanges(loadStatus: BitSet, totalSize: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var rangeStart: Int? = null

        for (i in 0 until totalSize) {
            val isLoaded = loadStatus.get(i)

            if (isLoaded && rangeStart == null) {
                rangeStart = i
            } else if (!isLoaded && rangeStart != null) {
                ranges.add(rangeStart until i)
                rangeStart = null
            }
        }

        if (rangeStart != null) {
            ranges.add(rangeStart until totalSize)
        }

        return ranges
    }

    /**
     * Shift all positions in a BitSet by a given amount.
     * Used when new messages are inserted at the beginning.
     *
     * @param loadStatus Original BitSet
     * @param shiftBy Amount to shift positions
     * @param totalSize New total size after shift
     * @return New BitSet with shifted positions
     */
    fun shiftBitSet(loadStatus: BitSet, shiftBy: Int, totalSize: Int): BitSet {
        val newLoadStatus = BitSet()
        for (i in 0 until loadStatus.length()) {
            if (loadStatus.get(i)) {
                val newPosition = i + shiftBy
                if (newPosition >= 0 && newPosition < totalSize) {
                    newLoadStatus.set(newPosition)
                }
            }
        }
        return newLoadStatus
    }
}
