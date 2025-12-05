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
 * Fireworks screen effect - rockets launch and explode into particles.
 *
 * From legacy fireworks_classes.dart + fireworks_rendering.dart:
 * - Rockets: 3-5 staggered launches
 * - Launch Phase: Accelerate upward (velocity × 1.025 per frame)
 * - Explosion: 96 particles per rocket
 * - Particle Physics:
 *   - Angle: Random 0-2π
 *   - Velocity: 1-13 units
 *   - Friction: 0.96 per frame
 *   - Gravity: 2.35 units
 *   - Alpha decay: 0.013-0.020
 * - Colors: HSV with 360° hue cycling
 * - Trails: 2 trail points per particle
 * - Duration: ~3.5 seconds
 */

data class Rocket(
    var x: Float,
    var y: Float,
    var velocityY: Float,
    var hue: Float,
    var exploded: Boolean = false
)

@Composable
fun FireworksEffect(
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val particlePool = remember { GlobalParticlePool.instance }

    var rockets by remember { mutableStateOf<List<Rocket>>(emptyList()) }
    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }
    var screenWidth by remember { mutableStateOf(0f) }
    var screenHeight by remember { mutableStateOf(0f) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Update rockets
            rockets = rockets.map { rocket ->
                if (!rocket.exploded) {
                    rocket.y += rocket.velocityY
                    rocket.velocityY *= 1.025f // Accelerate

                    // Explode when reaching target height
                    if (rocket.y < screenHeight * 0.3f + Random.nextFloat() * screenHeight * 0.3f) {
                        rocket.exploded = true

                        // Create explosion particles
                        val newParticles = List(96) {
                            val angle = Random.nextFloat() * PI.toFloat() * 2
                            val velocity = Random.nextFloat() * 12f + 1f
                            val hueOffset = Random.nextFloat() * 60f - 30f

                            particlePool.acquire().apply {
                                x = rocket.x
                                y = rocket.y
                                velocityX = cos(angle) * velocity
                                velocityY = sin(angle) * velocity

                                // HSV color based on rocket hue
                                color = Color.hsv(
                                    (rocket.hue + hueOffset).mod(360f),
                                    0.8f + Random.nextFloat() * 0.2f,
                                    0.9f + Random.nextFloat() * 0.1f
                                )

                                size = Random.nextFloat() * 3f + 2f
                                alpha = 1f
                                life = 1f
                                maxLife = Random.nextFloat() * 0.5f + 1.5f
                                isAlive = true

                                // Initialize trail
                                initTrail(2)
                            }
                        }
                        particles = particles + newParticles
                    }
                }
                rocket
            }

            // Update particles
            particles = particles.mapNotNull { particle ->
                if (!particle.isAlive) {
                    particlePool.release(particle)
                    return@mapNotNull null
                }

                // Update trail
                particle.updateTrail()

                // Apply physics
                particle.velocityX *= 0.96f // Friction
                particle.velocityY *= 0.96f
                particle.velocityY += 2.35f * 0.016f // Gravity

                particle.x += particle.velocityX
                particle.y += particle.velocityY

                // Alpha decay
                val decay = Random.nextFloat() * 0.007f + 0.013f
                particle.alpha -= decay
                particle.life -= 0.016f / particle.maxLife

                if (particle.alpha <= 0f || particle.life <= 0f) {
                    particle.isAlive = false
                }

                particle
            }

            // Check if complete
            if (elapsed > 4f || (elapsed > 2f && rockets.all { it.exploded } && particles.all { !it.isAlive })) {
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
            screenHeight = size.height

            // Create rockets with staggered launch
            val rocketCount = Random.nextInt(3, 6)
            rockets = List(rocketCount) { i ->
                Rocket(
                    x = size.width * (0.2f + Random.nextFloat() * 0.6f),
                    y = size.height + i * 100f,
                    velocityY = -(Random.nextFloat() * 3f + 8f),
                    hue = Random.nextFloat() * 360f
                )
            }
        }

        // Draw rocket trails
        rockets.filter { !it.exploded }.forEach { rocket ->
            drawCircle(
                color = Color.White,
                radius = 4f,
                center = Offset(rocket.x, rocket.y)
            )
            // Trailing spark
            drawCircle(
                color = Color(0xFFFFAB00),
                radius = 3f,
                center = Offset(rocket.x, rocket.y + 10f)
            )
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
                        radius = particle.size * (1f - i * 0.3f),
                        center = Offset(particle.trailX[trailIdx], particle.trailY[trailIdx])
                    )
                }
            }

            // Draw main particle
            drawCircle(
                color = particle.color.copy(alpha = particle.alpha.coerceIn(0f, 1f)),
                radius = particle.size,
                center = Offset(particle.x, particle.y)
            )
        }
    }
}
