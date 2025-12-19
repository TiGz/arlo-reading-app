package com.example.arlo.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.arlo.R
import kotlin.math.sin

/**
 * A custom view that renders animated sine waves representing voice input.
 * The wave amplitude responds to the microphone level (0-100).
 *
 * Features:
 * - Three overlapping sine waves with different phases for visual depth
 * - Smooth amplitude interpolation for responsive mic feedback
 * - Hardware-accelerated canvas drawing for performance
 * - Configurable wave color based on feedback state
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Wave rendering
    private val wavePath = Path()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Wave colors for different states
    private var primaryColor = ContextCompat.getColor(context, R.color.accent_purple)
    private var secondaryColor = ContextCompat.getColor(context, R.color.primary_light)
    private var tertiaryColor = ContextCompat.getColor(context, R.color.accent_purple)

    // Animation
    private var phase = 0f
    private val phaseAnimator = ValueAnimator.ofFloat(0f, 2 * Math.PI.toFloat()).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    // Amplitude control
    private var targetAmplitude = 0f
    private var currentAmplitude = 0f
    private val amplitudeSmoothing = 0.15f // Lower = smoother, higher = more responsive

    // State
    private var isActive = false

    init {
        // Enable hardware acceleration for smooth drawing
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Set the microphone level (0-100).
     * The wave amplitude will smoothly interpolate to this value.
     */
    fun setMicLevel(level: Int) {
        targetAmplitude = (level.coerceIn(0, 100) / 100f)
    }

    /**
     * Set the wave color scheme based on the feedback state.
     *
     * @param state One of: IDLE, LISTENING, SUCCESS, ERROR
     */
    fun setColorState(state: ColorState) {
        when (state) {
            ColorState.IDLE -> {
                primaryColor = ContextCompat.getColor(context, R.color.accent_purple)
                secondaryColor = adjustAlpha(primaryColor, 0.5f)
                tertiaryColor = adjustAlpha(primaryColor, 0.25f)
            }
            ColorState.LISTENING -> {
                primaryColor = ContextCompat.getColor(context, R.color.success)
                secondaryColor = adjustAlpha(primaryColor, 0.5f)
                tertiaryColor = adjustAlpha(primaryColor, 0.25f)
            }
            ColorState.SUCCESS -> {
                primaryColor = ContextCompat.getColor(context, R.color.success)
                secondaryColor = ContextCompat.getColor(context, R.color.accent_green)
                tertiaryColor = adjustAlpha(primaryColor, 0.4f)
            }
            ColorState.ERROR -> {
                primaryColor = ContextCompat.getColor(context, R.color.accent_orange)
                secondaryColor = adjustAlpha(primaryColor, 0.5f)
                tertiaryColor = adjustAlpha(primaryColor, 0.25f)
            }
        }
        invalidate()
    }

    /**
     * Start the wave animation.
     */
    fun startAnimation() {
        if (!isActive) {
            isActive = true
            phaseAnimator.start()
        }
    }

    /**
     * Stop the wave animation.
     */
    fun stopAnimation() {
        isActive = false
        phaseAnimator.cancel()
        targetAmplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Smooth amplitude interpolation
        currentAmplitude += (targetAmplitude - currentAmplitude) * amplitudeSmoothing

        val centerY = height / 2f
        val maxAmplitude = height * 0.4f // Max wave height is 40% of view height

        // Draw three waves with different phases and opacities
        drawWave(canvas, centerY, maxAmplitude * 0.3f, phase, tertiaryColor, 2f)
        drawWave(canvas, centerY, maxAmplitude * 0.6f, phase + 1f, secondaryColor, 2.5f)
        drawWave(canvas, centerY, maxAmplitude, phase + 2f, primaryColor, 3f)
    }

    private fun drawWave(
        canvas: Canvas,
        centerY: Float,
        baseAmplitude: Float,
        phaseOffset: Float,
        color: Int,
        strokeWidth: Float
    ) {
        wavePath.reset()
        wavePaint.color = color
        wavePaint.strokeWidth = strokeWidth * resources.displayMetrics.density

        val amplitude = baseAmplitude * currentAmplitude.coerceAtLeast(0.1f)
        val frequency = 3f // Number of complete waves across the view

        val step = 4 // Pixel step for smoother curve
        var isFirst = true

        for (x in 0..width step step) {
            val normalizedX = x.toFloat() / width
            val angle = (normalizedX * frequency * 2 * Math.PI + phaseOffset + phase).toFloat()

            // Add some variation with a secondary frequency
            val secondaryAngle = (normalizedX * frequency * 4 * Math.PI + phaseOffset * 2 + phase * 1.5f).toFloat()
            val y = centerY - (sin(angle) * amplitude + sin(secondaryAngle) * amplitude * 0.2f)

            if (isFirst) {
                wavePath.moveTo(x.toFloat(), y)
                isFirst = false
            } else {
                wavePath.lineTo(x.toFloat(), y)
            }
        }

        canvas.drawPath(wavePath, wavePaint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        phaseAnimator.cancel()
    }

    enum class ColorState {
        IDLE,
        LISTENING,
        SUCCESS,
        ERROR
    }
}
