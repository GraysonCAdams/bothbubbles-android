package com.bothbubbles.ui.effects.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lasers screen effect - disco laser beams emanating from center.
 *
 * From legacy laser_classes.dart + laser_rendering.dart:
 * - 12 laser beams
 * - Each beam oscillates within a π/2 (90°) range
 * - Width: 50-300px random, oscillates
 * - Global hue cycles every 500ms (random 360° jump)
 * - Radial gradient from center for glow effect
 * - Duration: ~5 seconds
 */

private data class LaserBeam(
    var globalAngle: Float,
    var internalWidth: Float,
    val originalGlobalAngle: Float,
    val originalInternalWidth: Float,
    val internalWidthVelocity: Float,
    val globalAngleVelocity: Float,
    val angleMin: Float,
    val angleMax: Float,
    var widthDirection: Int = 1, // 1 = up, -1 = down
    var angleDirection: Int = 1
)

@Composable
fun LasersEffect(
    messageBounds: Rect? = null,
    onComplete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var beams by remember { mutableStateOf<List<LaserBeam>>(emptyList()) }
    var globalHue by remember { mutableFloatStateOf(42f) }
    var startTime by remember { mutableLongStateOf(0L) }
    var lastHueChange by remember { mutableLongStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }
    var centerX by remember { mutableFloatStateOf(0f) }
    var centerY by remember { mutableFloatStateOf(0f) }
    var screenDiagonal by remember { mutableFloatStateOf(0f) }

    // Animation loop
    LaunchedEffect(Unit) {
        startTime = withFrameNanos { it }
        lastHueChange = startTime

        while (true) {
            val frameTime = withFrameNanos { it }
            val elapsed = (frameTime - startTime) / 1_000_000_000f

            if (!initialized) continue

            // Change hue every 500ms
            val timeSinceHueChange = (frameTime - lastHueChange) / 1_000_000f
            if (timeSinceHueChange > 500) {
                lastHueChange = frameTime
                globalHue = (globalHue + Random.nextFloat() * 360f) % 360f
            }

            // Update beams
            beams = beams.map { beam ->
                // Update internal width (oscillates between 25 and original)
                if (beam.internalWidth > beam.originalInternalWidth ||
                    (beam.widthDirection == -1 && beam.internalWidth >= 25f)
                ) {
                    beam.widthDirection = -1
                    beam.internalWidth -= beam.internalWidthVelocity
                }
                if (beam.internalWidth < 25f ||
                    (beam.widthDirection == 1 && beam.internalWidth <= beam.originalInternalWidth)
                ) {
                    beam.widthDirection = 1
                    beam.internalWidth += beam.internalWidthVelocity
                }

                // Update global angle (oscillates within quadrant)
                if (beam.globalAngle >= beam.angleMax ||
                    (beam.angleDirection == -1 && beam.globalAngle >= beam.angleMin)
                ) {
                    beam.angleDirection = -1
                    beam.globalAngle -= beam.globalAngleVelocity
                }
                if (beam.globalAngle <= beam.angleMin ||
                    (beam.angleDirection == 1 && beam.globalAngle <= beam.angleMax)
                ) {
                    beam.angleDirection = 1
                    beam.globalAngle += beam.globalAngleVelocity
                }

                beam
            }

            // Check if complete (5 seconds)
            if (elapsed > 5f) {
                onComplete()
                break
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!initialized) {
            initialized = true

            // Use message bounds center if provided, otherwise screen center
            centerX = messageBounds?.center?.x ?: (size.width / 2f)
            centerY = messageBounds?.center?.y ?: (size.height / 2f)
            screenDiagonal = max(size.height, size.width) * sqrt(2f)

            // Create 12 beams distributed across 4 quadrants
            beams = List(12) { index ->
                val quadrant = when {
                    index < 3 -> 0   // 0 to π/2
                    index < 6 -> 1   // π/2 to π
                    index < 9 -> 2   // π to 3π/2
                    else -> 3        // 3π/2 to 2π
                }

                val quadrantBase = quadrant * PI.toFloat() / 2f
                val width = (Random.nextFloat() * 250f + 50f).coerceIn(50f, 300f)
                val angle = quadrantBase + Random.nextFloat() * (PI.toFloat() / 2f)

                LaserBeam(
                    globalAngle = angle,
                    internalWidth = width,
                    originalGlobalAngle = quadrantBase,
                    originalInternalWidth = width,
                    internalWidthVelocity = width / 50f,
                    globalAngleVelocity = (angle / 50f) / ((index % 3) + 1),
                    angleMin = quadrantBase,
                    angleMax = quadrantBase + PI.toFloat() / 2f
                )
            }
        }

        val color = Color.hsv(globalHue, 1f, 1f)

        // Draw radial glow background
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.8f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = screenDiagonal
            ),
            radius = screenDiagonal,
            center = Offset(centerX, centerY)
        )

        // Draw each laser beam
        beams.forEach { beam ->
            val path = Path().apply {
                moveTo(centerX, centerY)

                // Calculate beam endpoints (triangle shape)
                val endX1 = centerX + screenDiagonal * cos(beam.globalAngle) -
                        beam.internalWidth * sin(beam.globalAngle)
                val endY1 = centerY + screenDiagonal * sin(beam.globalAngle) +
                        beam.internalWidth * cos(beam.globalAngle)

                val endX2 = centerX + screenDiagonal * cos(beam.globalAngle) +
                        beam.internalWidth * sin(beam.globalAngle)
                val endY2 = centerY + screenDiagonal * sin(beam.globalAngle) -
                        beam.internalWidth * cos(beam.globalAngle)

                lineTo(endX1, endY1)
                lineTo(endX2, endY2)
                close()
            }

            // Draw beam fill with radial gradient
            drawPath(
                path = path,
                brush = Brush.radialGradient(
                    colors = listOf(
                        color,
                        Color.Transparent
                    ),
                    center = Offset(centerX, centerY),
                    radius = screenDiagonal
                )
            )

            // Draw beam outline for definition
            drawPath(
                path = path,
                color = color.copy(alpha = 0.9f).lighten(0.1f),
                style = Stroke(width = 2f)
            )
        }

        // Draw center glow point
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    color,
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = 100f
            ),
            radius = 100f,
            center = Offset(centerX, centerY)
        )
    }
}

/**
 * Lightens a color by the given amount (0-1).
 */
private fun Color.lighten(amount: Float): Color {
    return Color(
        red = (red + amount).coerceIn(0f, 1f),
        green = (green + amount).coerceIn(0f, 1f),
        blue = (blue + amount).coerceIn(0f, 1f),
        alpha = alpha
    )
}
