package com.bothbubbles.ui.effects.particles

import java.util.ArrayDeque

/**
 * Object pool for particles to avoid garbage collection during animations.
 * Thread-safe for use in coroutines.
 */
class ParticlePool(
    private val initialSize: Int = 100,
    private val maxSize: Int = 500
) {
    private val pool = ArrayDeque<Particle>(initialSize)
    private val lock = Any()

    init {
        // Pre-populate pool
        repeat(initialSize) {
            pool.add(Particle())
        }
    }

    /**
     * Acquire a particle from the pool, or create a new one if empty.
     */
    fun acquire(): Particle {
        synchronized(lock) {
            return pool.pollFirst()?.also { it.reset() } ?: Particle()
        }
    }

    /**
     * Release a particle back to the pool for reuse.
     */
    fun release(particle: Particle) {
        synchronized(lock) {
            if (pool.size < maxSize) {
                particle.reset()
                pool.addLast(particle)
            }
            // If pool is full, particle will be garbage collected
        }
    }

    /**
     * Release multiple particles back to the pool.
     */
    fun releaseAll(particles: Collection<Particle>) {
        synchronized(lock) {
            particles.forEach { particle ->
                if (pool.size < maxSize) {
                    particle.reset()
                    pool.addLast(particle)
                }
            }
        }
    }

    /**
     * Clear all particles from the pool.
     */
    fun clear() {
        synchronized(lock) {
            pool.clear()
        }
    }

    /**
     * Current number of available particles in the pool.
     */
    val availableCount: Int
        get() = synchronized(lock) { pool.size }
}

/**
 * Global particle pool instance for sharing across effects.
 */
object GlobalParticlePool {
    val instance = ParticlePool(initialSize = 200, maxSize = 1000)
}
