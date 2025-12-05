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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bluebubbles.ui.effects.particles.GlobalParticlePool
import com.bluebubbles.ui.effects.particles.Particle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hearts screen effect - hearts expanding from center and floating upward.
 *
 * From legacy love_classes.dart + love_rendering.dart:
 * - Growth: Heart expands from center, size 1→200px
 * - Movement: Diverges diagonally upward after growth
 * - Velocity: 0.5 base, accelerates at 1.01× per frame
 * - Multiple hearts: Staggered spawns at different positions
 * - Duration: ~3 seconds
 */
@Composable
fun HeartsEffect(
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val particlePool = remember { GlobalParticlePool.instance }

    // Heart colors
    val colors = remember {
        listOf(
            Color(0xFFE91E63), // Pink
            Color(0xFFF44336), // Red
            Color(0xFFFF5252), // Light Red
            Color(0xFFFF4081), // Pink accent
            Color(0xFFE53935)  // Deep Red
        )
    }

    var hearts by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }
    var nextSpawnTime by remember { mutableStateOf(0f) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Spawn new hearts periodically
            if (elapsed > nextSpawnTime && elapsed < 2f) {
                val newHeart = particlePool.acquire().apply {
                    // Random position near center
                    x = Random.nextFloat() * 200f - 100f
                    y = Random.nextFloat() * 200f - 100f

                    // Random diagonal direction
                    val angle = Random.nextFloat() * PI.toFloat() * 2
                    velocityX = cos(angle) * 2f
                    velocityY = -sin(angle).coerceIn(0f, 1f) * 3f - 1f // Bias upward

                    size = 1f // Start small
                    scale = 1f
                    color = colors.random()
                    alpha = 1f
                    life = 1f
                    maxLife = 3f
                    isAlive = true
                }

                hearts = hearts + newHeart
                nextSpawnTime = elapsed + Random.nextFloat() * 0.3f + 0.1f
            }

            // Update hearts
            hearts = hearts.mapNotNull { heart ->
                if (!heart.isAlive) {
                    particlePool.release(heart)
                    return@mapNotNull null
                }

                // Grow until max size
                if (heart.size < 80f) {
                    heart.size += 3f
                }

                // Move with acceleration
                heart.velocityX *= 1.01f
                heart.velocityY *= 1.01f

                heart.x += heart.velocityX
                heart.y += heart.velocityY

                // Fade out
                heart.life -= 0.016f / heart.maxLife
                heart.alpha = heart.life.coerceIn(0f, 1f)

                if (heart.life <= 0f) {
                    heart.isAlive = false
                }

                heart
            }

            // Check if complete
            if (elapsed > 4f || (elapsed > 2.5f && hearts.all { !it.isAlive })) {
                hearts.forEach { particlePool.release(it) }
                onComplete()
                break
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!initialized) {
            initialized = true
        }

        val centerX = size.width / 2
        val centerY = size.height / 2

        hearts.filter { it.isAlive }.forEach { heart ->
            drawHeart(
                centerX = centerX + heart.x,
                centerY = centerY + heart.y,
                size = heart.size,
                color = heart.color,
                alpha = heart.alpha
            )
        }
    }
}

/**
 * Draw a heart shape using bezier curves
 */
private fun DrawScope.drawHeart(
    centerX: Float,
    centerY: Float,
    size: Float,
    color: Color,
    alpha: Float
) {
    val heartPath = Path().apply {
        // Starting at bottom point
        moveTo(centerX, centerY + size * 0.4f)

        // Left side curve
        cubicTo(
            centerX - size * 0.5f, centerY + size * 0.2f,
            centerX - size * 0.5f, centerY - size * 0.3f,
            centerX, centerY - size * 0.1f
        )

        // Right side curve
        cubicTo(
            centerX + size * 0.5f, centerY - size * 0.3f,
            centerX + size * 0.5f, centerY + size * 0.2f,
            centerX, centerY + size * 0.4f
        )

        close()
    }

    drawPath(
        path = heartPath,
        color = color.copy(alpha = alpha)
    )
}
