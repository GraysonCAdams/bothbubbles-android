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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bothbubbles.ui.effects.particles.GlobalParticlePool
import com.bothbubbles.ui.effects.particles.Particle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Balloons screen effect - colored balloons floating up from bottom.
 *
 * From legacy balloon_classes.dart + balloon_rendering.dart:
 * - Spawn: From bottom of screen, random X positions
 * - Radius: 40-100px random
 * - Velocity: 8 units upward
 * - Colors: Material Design palette
 * - Rendering: Bezier curves for balloon shape, gradient fill with 0.7 opacity
 * - String: Gray color, BlendMode.Screen
 * - Duration: ~4 seconds until all balloons exit top
 */
@Composable
fun BalloonsEffect(
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val particlePool = remember { GlobalParticlePool.instance }

    // Balloon colors - Material Design palette
    val colors = remember {
        listOf(
            Color(0xFFE53935), // Red
            Color(0xFFD81B60), // Pink
            Color(0xFF8E24AA), // Purple
            Color(0xFF1E88E5), // Blue
            Color(0xFF00ACC1), // Light Blue
            Color(0xFF43A047), // Green
            Color(0xFF7CB342), // Light Green
            Color(0xFFFB8C00), // Orange
            Color(0xFFFDD835)  // Yellow
        )
    }

    var balloons by remember { mutableStateOf<List<Particle>>(emptyList()) }
    var startTime by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Update balloons
            balloons = balloons.mapNotNull { balloon ->
                if (!balloon.isAlive) {
                    particlePool.release(balloon)
                    return@mapNotNull null
                }

                // Float upward
                balloon.y -= balloon.velocityY

                // Gentle wobble
                balloon.x += sin((elapsed + balloon.rotation) * 2 * PI.toFloat()) * 0.5f

                // Mark as dead if off screen
                if (balloon.y < -balloon.size * 2) {
                    balloon.isAlive = false
                }

                balloon
            }

            // Check if effect is complete
            if (elapsed > 5f || balloons.all { !it.isAlive }) {
                balloons.forEach { particlePool.release(it) }
                onComplete()
                break
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Initialize balloons on first draw
        if (!initialized) {
            initialized = true
            val balloonCount = Random.nextInt(12, 18)
            balloons = List(balloonCount) { i ->
                particlePool.acquire().apply {
                    x = Random.nextFloat() * canvasWidth
                    y = canvasHeight + Random.nextFloat() * 200f
                    velocityY = Random.nextFloat() * 3f + 6f // 6-9 units upward
                    this.size = Random.nextFloat() * 60f + 40f // 40-100px radius
                    color = colors.random()
                    rotation = Random.nextFloat() * 10f // Phase offset for wobble
                    alpha = 0.7f
                    isAlive = true
                }
            }
        }

        // Draw balloons
        balloons.filter { it.isAlive }.forEach { balloon ->
            drawBalloon(
                centerX = balloon.x,
                centerY = balloon.y,
                radius = balloon.size,
                color = balloon.color,
                alpha = balloon.alpha
            )
        }
    }
}

/**
 * Draw a balloon with string using bezier curves
 */
private fun DrawScope.drawBalloon(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color,
    alpha: Float
) {
    // Balloon body - oval shape
    val balloonPath = Path().apply {
        // Top of balloon
        moveTo(centerX, centerY - radius)

        // Right side curve
        cubicTo(
            centerX + radius * 1.2f, centerY - radius * 0.5f,
            centerX + radius * 1.2f, centerY + radius * 0.5f,
            centerX, centerY + radius * 0.9f
        )

        // Left side curve
        cubicTo(
            centerX - radius * 1.2f, centerY + radius * 0.5f,
            centerX - radius * 1.2f, centerY - radius * 0.5f,
            centerX, centerY - radius
        )

        close()
    }

    // Draw balloon with gradient
    drawPath(
        path = balloonPath,
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha * 0.9f),
                color.copy(alpha = alpha * 0.7f),
                color.copy(alpha = alpha * 0.5f)
            ),
            center = Offset(centerX - radius * 0.3f, centerY - radius * 0.3f),
            radius = radius * 1.5f
        )
    )

    // Balloon knot
    drawCircle(
        color = color.copy(alpha = alpha * 0.8f),
        radius = radius * 0.1f,
        center = Offset(centerX, centerY + radius * 0.95f)
    )

    // String
    val stringPath = Path().apply {
        moveTo(centerX, centerY + radius)
        quadraticBezierTo(
            centerX + radius * 0.3f, centerY + radius * 1.5f,
            centerX - radius * 0.2f, centerY + radius * 2f
        )
        quadraticBezierTo(
            centerX - radius * 0.5f, centerY + radius * 2.5f,
            centerX + radius * 0.1f, centerY + radius * 3f
        )
    }

    drawPath(
        path = stringPath,
        color = Color.Gray.copy(alpha = 0.6f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        blendMode = BlendMode.Screen
    )
}
