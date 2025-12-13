package com.bothbubbles.ui.util

import androidx.compose.runtime.Stable

/**
 * A stable wrapper around List that allows Compose to skip recomposition
 * when the list content hasn't changed.
 *
 * Collections are inherently unstable in Compose because they're interfaces
 * that could be backed by mutable implementations. This wrapper provides
 * stability guarantees by implementing proper equals/hashCode.
 *
 * Usage:
 * ```kotlin
 * @Stable
 * data class MyUiState(
 *     val items: StableList<Item>
 * )
 *
 * // In ViewModel:
 * _uiState.update { it.copy(items = newItems.toStable()) }
 *
 * // In Composable:
 * items(state.items.items) { item -> ... }
 * ```
 */
@Stable
class StableList<T>(val items: List<T>) : List<T> by items {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StableList<*>) return false
        return items == other.items
    }

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "StableList(size=${items.size})"
}

/**
 * Extension function to wrap a List in a StableList.
 */
fun <T> List<T>.toStable(): StableList<T> = StableList(this)

/**
 * A stable wrapper around Map.
 */
@Stable
class StableMap<K, V>(val map: Map<K, V>) : Map<K, V> by map {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StableMap<*, *>) return false
        return map == other.map
    }

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "StableMap(size=${map.size})"
}

/**
 * Extension function to wrap a Map in a StableMap.
 */
fun <K, V> Map<K, V>.toStable(): StableMap<K, V> = StableMap(this)

/**
 * A stable wrapper around Set.
 */
@Stable
class StableSet<T>(val set: Set<T>) : Set<T> by set {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StableSet<*>) return false
        return set == other.set
    }

    override fun hashCode(): Int = set.hashCode()

    override fun toString(): String = "StableSet(size=${set.size})"
}

/**
 * Extension function to wrap a Set in a StableSet.
 */
fun <T> Set<T>.toStable(): StableSet<T> = StableSet(this)
