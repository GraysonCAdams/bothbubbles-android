# Particle System

## Purpose

Efficient particle system for screen effects (confetti, balloons, etc.).

## Files

| File | Description |
|------|-------------|
| `Particle.kt` | Particle data class and physics |
| `ParticlePool.kt` | Object pool for particle reuse |

## Required Patterns

### Particle Definition

```kotlin
data class Particle(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var rotation: Float,
    var rotationSpeed: Float,
    var scale: Float,
    var alpha: Float,
    var color: Color,
    var lifetime: Float,
    var age: Float = 0f
) {
    val isAlive: Boolean get() = age < lifetime

    fun update(deltaTime: Float, gravity: Float = 0f) {
        age += deltaTime
        x += velocityX * deltaTime
        y += velocityY * deltaTime
        velocityY += gravity * deltaTime
        rotation += rotationSpeed * deltaTime
        alpha = 1f - (age / lifetime)
    }
}
```

### Particle Pool

```kotlin
class ParticlePool(private val maxSize: Int = 500) {
    private val pool = ArrayDeque<Particle>()
    private val active = mutableListOf<Particle>()

    fun obtain(): Particle {
        return pool.removeLastOrNull() ?: Particle(
            x = 0f, y = 0f, velocityX = 0f, velocityY = 0f,
            rotation = 0f, rotationSpeed = 0f, scale = 1f,
            alpha = 1f, color = Color.White, lifetime = 1f
        )
    }

    fun release(particle: Particle) {
        particle.age = 0f
        if (pool.size < maxSize) {
            pool.addLast(particle)
        }
    }

    fun update(deltaTime: Float) {
        active.forEach { it.update(deltaTime) }
        active.filter { !it.isAlive }.forEach { release(it) }
        active.removeAll { !it.isAlive }
    }

    fun spawn(count: Int, initializer: (Particle) -> Unit) {
        repeat(count) {
            val particle = obtain()
            initializer(particle)
            active.add(particle)
        }
    }
}
```

## Best Practices

1. Use object pooling to avoid allocations
2. Limit max particle count
3. Use Canvas for rendering (not Composables)
4. Update physics on UI thread
5. Clean up when effect completes
