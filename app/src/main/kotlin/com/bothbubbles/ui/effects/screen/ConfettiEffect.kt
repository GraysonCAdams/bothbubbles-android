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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bothbubbles.ui.effects.particles.GlobalParticlePool
import com.bothbubbles.ui.effects.particles.Particle
import kotlin.random.Random

/**
 * Confetti screen effect - multicolored particles falling from top.
 *
 * - Particles: 80-120 from top of screen
 * - Colors: Material Design rainbow palette
 * - Velocity: Random X drift, 200-500 downward
 * - Rotation: Random initial + rotationVelocity
 * - Physics: Gravity 400, air resistance 0.99
 * - Duration: ~4 seconds
 */
@Composable
fun ConfettiEffect(
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val particlePool = remember { GlobalParticlePool.instance }

    // Confetti colors - Material Design palette
    val colors = remember {
        listOf(
            Color(0xFFE53935), // Red
            Color(0xFFD81B60), // Pink
            Color(0xFF8E24AA), // Purple
            Color(0xFF5E35B1), // Deep Purple
            Color(0xFF3949AB), // Indigo
            Color(0xFF1E88E5), // Blue
            Color(0xFF00ACC1), // Cyan
            Color(0xFF00897B), // Teal
            Color(0xFF43A047), // Green
            Color(0xFF7CB342), // Light Green
            Color(0xFFC0CA33), // Lime
            Color(0xFFFDD835), // Yellow
            Color(0xFFFFB300), // Amber
            Color(0xFFFB8C00), // Orange
            Color(0xFFF4511E)  // Deep Orange
        )
    }

    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        // Run animation loop
        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            // Initialize particles on first frame when we have size
            if (!initialized) {
                continue
            }

            // Update particles
            particles = particles.mapNotNull { particle ->
                if (!particle.isAlive) {
                    particlePool.release(particle)
                    return@mapNotNull null
                }

                // Apply physics
                particle.velocityY += 400f * 0.016f // Gravity
                particle.velocityX *= 0.99f // Air resistance

                particle.x += particle.velocityX * 0.016f
                particle.y += particle.velocityY * 0.016f
                particle.rotation += particle.rotationVelocity * 0.016f

                // Life decay
                particle.life -= 0.016f / particle.maxLife

                // Mark as dead if off screen or life depleted
                if (particle.life <= 0f || particle.y > 2000f) {
                    particle.isAlive = false
                }

                particle
            }

            // Check if effect is complete
            if (elapsed > 4f || particles.all { !it.isAlive }) {
                // Release remaining particles
                particles.forEach { particlePool.release(it) }
                onComplete()
                break
            }
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val canvasWidth = size.width

        // Initialize particles on first draw when we have size
        if (!initialized) {
            initialized = true
            val particleCount = Random.nextInt(80, 120)
            particles = List(particleCount) {
                particlePool.acquire().apply {
                    x = Random.nextFloat() * canvasWidth
                    y = -Random.nextFloat() * 100f - 20f
                    velocityX = Random.nextFloat() * 200f - 100f
                    velocityY = Random.nextFloat() * 300f + 200f
                    rotation = Random.nextFloat() * 360f
                    rotationVelocity = Random.nextFloat() * 360f - 180f
                    color = colors.random()
                    this.size = Random.nextFloat() * 8f + 6f
                    life = 1f
                    maxLife = Random.nextFloat() * 2f + 3f
                    isAlive = true
                }
            }
        }

        // Draw particles
        particles.filter { it.isAlive }.forEach { particle ->
            rotate(
                degrees = particle.rotation,
                pivot = Offset(particle.x, particle.y)
            ) {
                drawRect(
                    color = particle.color.copy(alpha = particle.life.coerceIn(0f, 1f)),
                    topLeft = Offset(
                        particle.x - particle.size / 2,
                        particle.y - particle.size
                    ),
                    size = Size(particle.size, particle.size * 2)
                )
            }
        }
    }
}
