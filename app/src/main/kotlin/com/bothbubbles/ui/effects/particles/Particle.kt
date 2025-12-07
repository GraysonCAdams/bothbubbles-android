package com.bothbubbles.ui.effects.particles

import androidx.compose.ui.graphics.Color

/**
 * Represents a single particle in particle-based effects.
 * Mutable for performance - particles are updated in-place during animation.
 */
data class Particle(
    var x: Float = 0f,
    var y: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var rotation: Float = 0f,
    var rotationVelocity: Float = 0f,
    var scale: Float = 1f,
    var alpha: Float = 1f,
    var color: Color = Color.White,
    var size: Float = 10f,
    var life: Float = 1f,
    var maxLife: Float = 1f,
    var isAlive: Boolean = true,

    // Trail support for fireworks
    var trailX: FloatArray = FloatArray(0),
    var trailY: FloatArray = FloatArray(0),
    var trailIndex: Int = 0
) {
    /**
     * Reset particle to default state for object pooling reuse
     */
    fun reset() {
        x = 0f
        y = 0f
        velocityX = 0f
        velocityY = 0f
        rotation = 0f
        rotationVelocity = 0f
        scale = 1f
        alpha = 1f
        color = Color.White
        size = 10f
        life = 1f
        maxLife = 1f
        isAlive = true
        trailIndex = 0
    }

    /**
     * Initialize trail arrays with given size
     */
    fun initTrail(trailCount: Int) {
        if (trailX.size != trailCount) {
            trailX = FloatArray(trailCount)
            trailY = FloatArray(trailCount)
        }
        trailIndex = 0
    }

    /**
     * Add current position to trail
     */
    fun updateTrail() {
        if (trailX.isNotEmpty()) {
            trailX[trailIndex] = x
            trailY[trailIndex] = y
            trailIndex = (trailIndex + 1) % trailX.size
        }
    }
}

/**
 * Configuration for particle physics behavior
 */
data class ParticleConfig(
    val gravity: Float = 0f,
    val friction: Float = 1f,
    val alphaDecay: Float = 0f,
    val scaleDecay: Float = 0f,
    val windX: Float = 0f,
    val windY: Float = 0f
)

/**
 * Particle shape types for rendering
 */
enum class ParticleShape {
    Circle,
    Rectangle,
    Heart,
    Star,
    Balloon
}
