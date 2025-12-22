package com.example.arlo.games

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.DialogFragment
import com.example.arlo.R
import com.example.arlo.databinding.DialogGameUnlockBinding

/**
 * Celebration dialog shown when a game reward is unlocked.
 * Features animated racing car and race count display.
 */
class GameUnlockDialog : DialogFragment() {

    private var _binding: DialogGameUnlockBinding? = null
    private val binding get() = _binding!!

    private var racesEarned: Int = 1
    private var onPlayNow: (() -> Unit)? = null
    private var onSaveForLater: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Arlo_Dialog_Fullscreen)
        racesEarned = arguments?.getInt(ARG_RACES_EARNED, 1) ?: 1
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGameUnlockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        playEntranceAnimation()
    }

    private fun setupUI() {
        // Set race count text
        val raceText = if (racesEarned == 1) {
            "You earned 1 race!"
        } else {
            "You earned $racesEarned races!"
        }
        binding.tvRaceCount.text = raceText

        // Subtitle based on race count
        binding.tvSubtitle.text = when (racesEarned) {
            1 -> "Great job meeting your goal!"
            2 -> "Excellent work! Bonus race earned!"
            else -> "Amazing! Maximum races unlocked!"
        }

        // Play now button
        binding.btnPlayNow.setOnClickListener {
            onPlayNow?.invoke()
            dismiss()
        }

        // Save for later button
        binding.btnSaveForLater.setOnClickListener {
            onSaveForLater?.invoke()
            dismiss()
        }

        // Dismiss on background tap
        binding.root.setOnClickListener {
            // Don't dismiss on background tap - force user to choose
        }

        binding.cardContent.setOnClickListener {
            // Consume click to prevent dismissal
        }
    }

    private fun playEntranceAnimation() {
        // Initial state
        binding.cardContent.alpha = 0f
        binding.cardContent.scaleX = 0.8f
        binding.cardContent.scaleY = 0.8f
        binding.ivRacingCar.translationX = -500f
        binding.ivRacingCar.rotation = -15f

        // Card entrance with dramatic pop
        val cardFadeIn = ObjectAnimator.ofFloat(binding.cardContent, View.ALPHA, 0f, 1f)
        val cardScaleX = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_X, 0.6f, 1.05f, 1f)
        val cardScaleY = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_Y, 0.6f, 1.05f, 1f)

        val cardAnimSet = AnimatorSet().apply {
            playTogether(cardFadeIn, cardScaleX, cardScaleY)
            duration = 400
            interpolator = OvershootInterpolator(1.5f)
        }

        // Racing car zoom-in with rotation
        val carZoom = ObjectAnimator.ofFloat(binding.ivRacingCar, View.TRANSLATION_X, -500f, 20f, 0f)
        val carRotation = ObjectAnimator.ofFloat(binding.ivRacingCar, View.ROTATION, -15f, 5f, 0f)
        val carAnimSet = AnimatorSet().apply {
            playTogether(carZoom, carRotation)
            duration = 600
            startDelay = 200
            interpolator = OvershootInterpolator(0.9f)
        }

        // Race count entrance with scale bounce
        val countBounce = AnimatorSet().apply {
            val scaleUpX = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_X, 0.5f, 1.3f, 1f)
            val scaleUpY = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_Y, 0.5f, 1.3f, 1f)
            playTogether(scaleUpX, scaleUpY)
            startDelay = 500
            duration = 500
            interpolator = OvershootInterpolator(2.5f)
        }

        // Button slide-up
        val btnPlaySlide = ObjectAnimator.ofFloat(binding.btnPlayNow, View.TRANSLATION_Y, 50f, 0f)
        val btnPlayFade = ObjectAnimator.ofFloat(binding.btnPlayNow, View.ALPHA, 0f, 1f)
        val btnSaveSlide = ObjectAnimator.ofFloat(binding.btnSaveForLater, View.TRANSLATION_Y, 50f, 0f)
        val btnSaveFade = ObjectAnimator.ofFloat(binding.btnSaveForLater, View.ALPHA, 0f, 1f)

        binding.btnPlayNow.alpha = 0f
        binding.btnSaveForLater.alpha = 0f

        val buttonAnimSet = AnimatorSet().apply {
            playTogether(btnPlaySlide, btnPlayFade, btnSaveSlide, btnSaveFade)
            startDelay = 700
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Play all animations
        AnimatorSet().apply {
            playTogether(cardAnimSet, carAnimSet, countBounce, buttonAnimSet)
            start()
        }

        // Start confetti after card appears
        Handler(Looper.getMainLooper()).postDelayed({
            binding.confettiView.startConfetti()
        }, 300)

        // Pulse the racing car icon periodically for extra celebration
        startCarPulseAnimation()
    }

    private fun startCarPulseAnimation() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (_binding == null) return@postDelayed

            val pulseX = ObjectAnimator.ofFloat(binding.ivRacingCar, View.SCALE_X, 1f, 1.1f, 1f)
            val pulseY = ObjectAnimator.ofFloat(binding.ivRacingCar, View.SCALE_Y, 1f, 1.1f, 1f)
            AnimatorSet().apply {
                playTogether(pulseX, pulseY)
                duration = 600
                interpolator = OvershootInterpolator(1.5f)
                start()
            }
        }, 1500)
    }

    fun setOnPlayNowListener(listener: () -> Unit) {
        onPlayNow = listener
    }

    fun setOnSaveForLaterListener(listener: () -> Unit) {
        onSaveForLater = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RACES_EARNED = "races_earned"

        fun newInstance(racesEarned: Int): GameUnlockDialog {
            return GameUnlockDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_RACES_EARNED, racesEarned)
                }
            }
        }
    }
}
