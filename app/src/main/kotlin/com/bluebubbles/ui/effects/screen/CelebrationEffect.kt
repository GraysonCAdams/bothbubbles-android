package com.bluebubbles.ui.effects.screen

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
import androidx.compose.ui.graphics.Color
import com.bluebubbles.ui.effects.particles.GlobalParticlePool
import com.bluebubbles.ui.effects.particles.Particle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Celebration screen effect - rapid-fire sparkle fireworks in orange/gold.
 *
 * From legacy celebration_class.dart:
 * - Extends fireworks behavior
 * - Explosions: 10 per frame (rapid fire)
 * - Spawn position: Top-right corner
 * - Hue: Fixed at 28 (orange/gold)
 * - Saturation: 0.5
 * - Particle size: 10px
 * - Trails: 2 points
 * - Duration: ~3 seconds
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
    var lastSpawnTime by remember { mutableLongStateOf(0L) }
    var stopSpawning by remember { mutableStateOf(false) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }
        lastSpawnTime = startTime

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Spawn rapid-fire bursts for first 2 seconds
            if (!stopSpawning && elapsed < 2f) {
                val timeSinceSpawn = (frameTime - lastSpawnTime) / 1_000_000f // ms
                if (timeSinceSpawn > 100) { // Every 100ms
                    lastSpawnTime = frameTime

                    // Create 10 celebration particles per burst (rapid fire)
                    repeat(10) {
                        // Random spawn point in top-right area
                        val spawnX = screenWidth * (0.6f + Random.nextFloat() * 0.4f)
                        val spawnY = Random.nextFloat() * 100f

                        val newParticles = List(12) {
                            val angle = Random.nextFloat() * PI.toFloat() * 2
                            val velocity = Random.nextFloat() * 10f + 3f
                            val hueOffset = Random.nextFloat() * 20f - 10f

                            particlePool.acquire().apply {
                                x = spawnX
                                y = spawnY
                                velocityX = cos(angle) * velocity
                                velocityY = sin(angle) * velocity

                                // Orange/gold color with slight variation (hue 28)
                                color = Color.hsv(
                                    (28f + hueOffset).mod(360f),
                                    0.5f + Random.nextFloat() * 0.3f,
                                    0.9f + Random.nextFloat() * 0.1f
                                )

                                size = 10f
                                alpha = 1f
                                life = 1f
                                maxLife = Random.nextFloat() * 0.5f + 1f
                                isAlive = true

                                // Initialize trail
                                initTrail(2)
                            }
                        }
                        particles = particles + newParticles
                    }
                }
            } else if (elapsed >= 2f) {
                stopSpawning = true
            }

            // Update particles with firework physics
            particles = particles.mapNotNull { particle ->
                if (!particle.isAlive) {
                    particlePool.release(particle)
                    return@mapNotNull null
                }

                // Update trail
                particle.updateTrail()

                // Apply physics (similar to fireworks)
                particle.velocityX *= 0.96f // Friction
                particle.velocityY *= 0.96f
                particle.velocityY += 2.0f * 0.016f // Gravity (slightly less than fireworks)

                particle.x += particle.velocityX
                particle.y += particle.velocityY

                // Alpha decay
                val decay = Random.nextFloat() * 0.01f + 0.015f
                particle.alpha -= decay
                particle.life -= 0.016f / particle.maxLife

                if (particle.alpha <= 0f || particle.life <= 0f) {
                    particle.isAlive = false
                }

                particle
            }

            // Check if complete
            if (elapsed > 4f || (stopSpawning && particles.all { !it.isAlive })) {
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

        // Draw particles with trails
        particles.filter { it.isAlive }.forEach { particle ->
            // Draw trail points
            for (i in 0 until particle.trailX.size) {
                val trailIdx = (particle.trailIndex - 1 - i + particle.trailX.size) % particle.trailX.size
                val trailAlpha = particle.alpha * (1f - i * 0.4f)
                if (trailAlpha > 0) {
                    drawCircle(
                        color = particle.color.copy(alpha = trailAlpha.coerceIn(0f, 1f)),
                        radius = particle.size * (1f - i * 0.25f),
                        center = Offset(particle.trailX[trailIdx], particle.trailY[trailIdx])
                    )
                }
            }

            // Draw main particle with glow effect
            // Outer glow
            drawCircle(
                color = particle.color.copy(alpha = (particle.alpha * 0.3f).coerceIn(0f, 1f)),
                radius = particle.size * 2f,
                center = Offset(particle.x, particle.y)
            )

            // Main particle
            drawCircle(
                color = particle.color.copy(alpha = particle.alpha.coerceIn(0f, 1f)),
                radius = particle.size,
                center = Offset(particle.x, particle.y)
            )

            // Bright center
            drawCircle(
                color = Color.White.copy(alpha = (particle.alpha * 0.8f).coerceIn(0f, 1f)),
                radius = particle.size * 0.4f,
                center = Offset(particle.x, particle.y)
            )
        }
    }
}
