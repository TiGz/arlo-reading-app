package com.example.arlo.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.example.arlo.R

/**
 * Individual word view with animation capabilities.
 * Used within AnimatedSentenceView to create word-level animations.
 */
class WordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    // Current animation state
    private var currentState = WordHighlightState.IDLE
    private var currentAnimator: AnimatorSet? = null
    private var pulseAnimator: ValueAnimator? = null

    // Base colors from theme
    private val defaultTextColor by lazy { ContextCompat.getColor(context, R.color.reader_text_primary) }
    private val ttsHighlightColor by lazy { ContextCompat.getColor(context, R.color.highlight_word) }
    private val userTurnColor by lazy { ContextCompat.getColor(context, R.color.highlight_success) }
    private val listeningColor by lazy { ContextCompat.getColor(context, R.color.highlight_collaborative) }
    private val successColor by lazy { ContextCompat.getColor(context, R.color.success) }
    private val errorColor by lazy { ContextCompat.getColor(context, R.color.error) }

    // Animation constants - tuned for children's reading app
    companion object {
        // TTS speaking: word grows then shrinks
        const val TTS_SCALE_UP = 1.08f
        const val TTS_GROW_DURATION = 200L
        const val TTS_SHRINK_DURATION = 250L

        // User turn: gentle continuous pulse
        const val PULSE_SCALE = 1.12f
        const val PULSE_DURATION = 1000L

        // Success: celebratory bounce
        const val SUCCESS_SCALE = 1.2f
        const val SUCCESS_DURATION = 400L

        // Error: shake animation
        const val SHAKE_DISTANCE = 8f
        const val SHAKE_DURATION = 300L
    }

    init {
        // Base styling - matches existing reader aesthetics
        setTypeface(Typeface.SERIF, Typeface.NORMAL)
        setTextColor(defaultTextColor)
        textSize = 30f // Will be overridden by parent

        // Enable hardware acceleration for smooth animations
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Set pivot to center for scale animations (must be done after layout)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    /**
     * Transition to a new highlight state with appropriate animation.
     */
    fun transitionTo(newState: WordHighlightState) {
        Log.d("WordView", "transitionTo: '${text}' from $currentState to $newState")

        if (newState == currentState) {
            Log.d("WordView", "Skipping - already in state $newState")
            return
        }

        // Cancel any running animation
        cancelAnimations()

        currentState = newState

        when (newState) {
            WordHighlightState.IDLE -> animateToIdle()
            WordHighlightState.TTS_SPEAKING -> animateTTSSpeaking()
            WordHighlightState.USER_TURN -> animateUserTurn()
            WordHighlightState.LISTENING -> animateListening()
            WordHighlightState.SUCCESS -> animateSuccess()
            WordHighlightState.ERROR -> animateError()
        }
    }

    /**
     * Reset to default state without animation.
     */
    fun resetImmediate() {
        cancelAnimations()
        currentState = WordHighlightState.IDLE
        scaleX = 1.0f
        scaleY = 1.0f
        translationX = 0f
        translationY = 0f
        setTextColor(defaultTextColor)
        background = null
    }

    private fun cancelAnimations() {
        currentAnimator?.cancel()
        currentAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    // ==================== Animation Implementations ====================

    /**
     * Return to idle state - shrink back to normal size and restore black text.
     */
    private fun animateToIdle() {
        // Animate back to normal size
        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(TTS_SHRINK_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withStartAction {
                // Restore text color immediately
                setTextColor(defaultTextColor)
                background = null
            }
            .start()
    }

    /**
     * TTS is speaking this word - grow bigger + change to purple.
     * Word stays enlarged and purple until next word is spoken.
     */
    private fun animateTTSSpeaking() {
        // Change text color to purple (no background)
        setTextColor(listeningColor)
        background = null

        // Grow animation
        animate()
            .scaleX(TTS_SCALE_UP)
            .scaleY(TTS_SCALE_UP)
            .setDuration(TTS_GROW_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * User's turn to read this word - simple green highlight (no animation).
     */
    private fun animateUserTurn() {
        // Apply green highlight background - no animation, just highlight
        setBackgroundColor(userTurnColor)
    }

    /**
     * Actively listening for speech - purple highlight (no animation).
     */
    private fun animateListening() {
        // Apply purple highlight background - no animation, just highlight
        setBackgroundColor(listeningColor)
    }

    /**
     * User spoke correctly - celebratory bounce with green flash.
     */
    private fun animateSuccess() {
        // Flash green
        setBackgroundColor(successColor)

        // Bounce animation on Y axis for "jump" effect
        val bounceY = ObjectAnimator.ofFloat(
            this, "translationY",
            0f, -16f, 0f, -8f, 0f
        ).apply {
            duration = SUCCESS_DURATION
            interpolator = BounceInterpolator()
        }

        // Scale bounce
        val scaleX = ObjectAnimator.ofFloat(
            this, "scaleX",
            1.0f, SUCCESS_SCALE, 1.0f
        ).apply {
            duration = SUCCESS_DURATION
            interpolator = BounceInterpolator()
        }

        val scaleY = ObjectAnimator.ofFloat(
            this, "scaleY",
            1.0f, SUCCESS_SCALE, 1.0f
        ).apply {
            duration = SUCCESS_DURATION
            interpolator = BounceInterpolator()
        }

        currentAnimator = AnimatorSet().apply {
            playTogether(bounceY, scaleX, scaleY)
            start()
        }
    }

    /**
     * User spoke incorrectly - shake effect with red flash.
     */
    private fun animateError() {
        // Flash red
        setBackgroundColor(errorColor)

        // Shake animation on X axis
        val shake = ObjectAnimator.ofFloat(
            this, "translationX",
            0f, -SHAKE_DISTANCE, SHAKE_DISTANCE, -SHAKE_DISTANCE, SHAKE_DISTANCE, 0f
        ).apply {
            duration = SHAKE_DURATION
        }

        currentAnimator = AnimatorSet().apply {
            play(shake)
            start()
        }
    }
}
