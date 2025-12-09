package com.bothbubbles.ui.effects.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bothbubbles.ui.effects.particles.GlobalParticlePool
import com.bothbubbles.ui.effects.particles.Particle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Celebration screen effect - sparkle/glitter particles from the corner.
 *
 * From legacy celebration_class.dart + fireworks_classes.dart:
 * - Particles spawn from top-right corner (windowSize.width, 0)
 * - High velocity: random * 50 + 1 (very fast spread)
 * - Fixed hue: 28 (orange/gold) with no variation
 * - Saturation: 0.5
 * - Particle size: random 0-10
 * - Trails: 2 points
 * - 96 particles per burst, 10 bursts created at once
 * - Continuous spawning: creates new bursts when particles deplete
 * - Rendered with BlendMode.screen for additive sparkle effect
 * - Duration: ~3-4 seconds
 */

@Composable
fun CelebrationEffect(
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val particlePool = remember { GlobalParticlePool.instance }

    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }
    var screenWidth by remember { mutableStateOf(0f) }
    var hasCreatedParticles by remember { mutableStateOf(false) }
    var stopSpawning by remember { mutableStateOf(false) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Stop spawning after 2 seconds
            if (elapsed >= 2f) {
                stopSpawning = true
            }

            // Legacy behavior: spawn new bursts when particles are empty (or initially)
            // Creates 10 bursts of 96 particles each = 960 particles total per spawn cycle
            val aliveParticles = particles.filter { it.isAlive }
            if (!stopSpawning && aliveParticles.isEmpty()) {
                val newParticles = mutableListOf<Particle>()

                // Create 10 bursts (like legacy _createCelebration called in a loop)
                repeat(10) {
                    // Each burst has 96 particles (explosionParticleCount)
                    repeat(96) {
                        val angle = Random.nextFloat() * PI.toFloat() * 2
                        // Legacy: velocity = random * 50 + 1 (high velocity for sparkle spread)
                        val velocity = Random.nextFloat() * 50f + 1f

                        newParticles.add(particlePool.acquire().apply {
                            // Legacy spawn: exactly top-right corner
                            x = screenWidth
                            y = 0f
                            velocityX = cos(angle) * velocity
                            velocityY = sin(angle) * velocity

                            // Legacy: fixed hue 28, saturation 0.5, brightness 0.5-0.8
                            color = Color.hsv(
                                28f,
                                0.5f,
                                0.5f + Random.nextFloat() * 0.3f
                            )

                            // Legacy: size is random 0-10
                            size = Random.nextFloat() * 10f
                            alpha = 1f
                            life = 1f
                            maxLife = 2f
                            isAlive = true

                            // Initialize trail (legacy uses 2 trail points for celebration)
                            initTrail(2)
                        })
                    }
                }

                particles = newParticles
                hasCreatedParticles = true
            }

            // Update particles with legacy physics
            particles = particles.mapNotNull { particle ->
                if (!particle.isAlive) {
                    particlePool.release(particle)
                    return@mapNotNull null
                }

                // Update trail before moving
                particle.updateTrail()

                // Legacy physics from fireworks_classes.dart
                particle.velocityX *= 0.96f // Friction
                particle.velocityY *= 0.96f
                // Legacy: gravity = 2.35 applied directly to position
                particle.velocityY += 2.35f

                particle.x += particle.velocityX
                particle.y += particle.velocityY

                // Legacy alpha decay: random * 0.007 + 0.013
                val decay = Random.nextFloat() * 0.007f + 0.013f
                particle.alpha -= decay

                if (particle.alpha <= 0f) {
                    particle.isAlive = false
                }

                particle
            }

            // Check if complete
            val allDead = particles.all { !it.isAlive }
            if (elapsed > 5f || (stopSpawning && hasCreatedParticles && allDead)) {
                particles.forEach { particlePool.release(it) }
                onComplete()
                break
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!initialized) {
            initialized = true
            screenWidth = size.width
        }

        // Draw particles with legacy rendering style
        // Legacy uses drawPath with lines from trail to current position
        // with BlendMode.screen for additive sparkle effect
        particles.filter { it.isAlive }.forEach { particle ->
            drawCelebrationParticle(particle)
        }
    }
}

/**
 * Draw a celebration particle with legacy rendering style.
 * Legacy draws a line from the last trail point to current position
 * with BlendMode.screen for additive brightness.
 */
private fun DrawScope.drawCelebrationParticle(particle: Particle) {
    if (particle.trailX.isEmpty()) return

    // Get last trail point
    val lastTrailIdx = (particle.trailIndex - 1 + particle.trailX.size) % particle.trailX.size
    val trailX = particle.trailX[lastTrailIdx]
    val trailY = particle.trailY[lastTrailIdx]

    // Only draw if trail has valid data (not at origin)
    val hasTrailData = trailX != 0f || trailY != 0f

    if (hasTrailData) {
        // Legacy: draw line from trail to current position with BlendMode.screen
        drawLine(
            color = particle.color.copy(alpha = particle.alpha.coerceIn(0f, 1f)),
            start = Offset(trailX, trailY),
            end = Offset(particle.x, particle.y),
            strokeWidth = particle.size,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Screen
        )
    } else {
        // Fallback: draw circle if no trail data yet
        drawCircle(
            color = particle.color.copy(alpha = particle.alpha.coerceIn(0f, 1f)),
            radius = particle.size / 2f,
            center = Offset(particle.x, particle.y),
            blendMode = BlendMode.Screen
        )
    }
}
