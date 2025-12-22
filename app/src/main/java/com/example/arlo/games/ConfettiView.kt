package com.example.arlo.games

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A custom view that renders animated confetti particles for celebrations.
 * Particles burst from the center and fall with physics-like motion.
 */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    // Celebration colors
    private val colors = intArrayOf(
        0xFFFF6B6B.toInt(),  // Coral
        0xFFFFD93D.toInt(),  // Gold
        0xFF6BCB77.toInt(),  // Green
        0xFF4D96FF.toInt(),  // Blue
        0xFFAE7EDE.toInt(),  // Purple
        0xFFFF9F43.toInt()   // Orange
    )

    data class Particle(
        var x: Float,
        var y: Float,
        var velocityX: Float,
        var velocityY: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var size: Float,
        var color: Int,
        var alpha: Float = 1f,
        var shape: Shape = Shape.RECTANGLE
    )

    enum class Shape { RECTANGLE, CIRCLE, STAR }

    fun startConfetti() {
        particles.clear()

        val centerX = width / 2f
        val centerY = height / 2f

        // Create burst of particles
        repeat(60) {
            val angle = Random.nextFloat() * 2 * Math.PI
            val speed = Random.nextFloat() * 15 + 8
            val shape = Shape.values()[Random.nextInt(Shape.values().size)]

            particles.add(
                Particle(
                    x = centerX,
                    y = centerY,
                    velocityX = (cos(angle) * speed).toFloat(),
                    velocityY = (sin(angle) * speed).toFloat() - 10, // Upward bias
                    rotation = Random.nextFloat() * 360,
                    rotationSpeed = Random.nextFloat() * 10 - 5,
                    size = Random.nextFloat() * 12 + 6,
                    color = colors[Random.nextInt(colors.size)],
                    shape = shape
                )
            )
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            interpolator = LinearInterpolator()
            addUpdateListener {
                updateParticles()
                invalidate()
            }
            start()
        }
    }

    private fun updateParticles() {
        val gravity = 0.4f
        val friction = 0.98f

        particles.forEach { particle ->
            particle.velocityY += gravity
            particle.velocityX *= friction
            particle.x += particle.velocityX
            particle.y += particle.velocityY
            particle.rotation += particle.rotationSpeed

            // Fade out as particles fall
            if (particle.y > height * 0.7) {
                particle.alpha = maxOf(0f, particle.alpha - 0.02f)
            }
        }

        // Remove dead particles
        particles.removeAll { it.alpha <= 0 || it.y > height + 50 }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        particles.forEach { particle ->
            paint.color = particle.color
            paint.alpha = (particle.alpha * 255).toInt()

            canvas.save()
            canvas.translate(particle.x, particle.y)
            canvas.rotate(particle.rotation)

            when (particle.shape) {
                Shape.RECTANGLE -> {
                    canvas.drawRect(
                        -particle.size / 2,
                        -particle.size / 4,
                        particle.size / 2,
                        particle.size / 4,
                        paint
                    )
                }
                Shape.CIRCLE -> {
                    canvas.drawCircle(0f, 0f, particle.size / 2, paint)
                }
                Shape.STAR -> {
                    drawStar(canvas, particle.size / 2, paint)
                }
            }

            canvas.restore()
        }
    }

    private fun drawStar(canvas: Canvas, radius: Float, paint: Paint) {
        val points = 5
        val innerRadius = radius * 0.4f
        val path = android.graphics.Path()

        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) radius else innerRadius
            val angle = Math.PI / 2 + i * Math.PI / points
            val x = (r * cos(angle)).toFloat()
            val y = (r * sin(angle)).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
