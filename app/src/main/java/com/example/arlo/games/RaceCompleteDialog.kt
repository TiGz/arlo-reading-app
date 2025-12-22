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
import com.example.arlo.databinding.DialogRaceCompleteBinding

/**
 * Dialog shown after completing a race session.
 * Displays race results and remaining races count.
 */
class RaceCompleteDialog : DialogFragment() {

    private var _binding: DialogRaceCompleteBinding? = null
    private val binding get() = _binding!!

    private var position: Int = 1
    private var racesRemaining: Int = 0
    private var onPlayAgain: (() -> Unit)? = null
    private var onBackToReading: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Arlo_Dialog_Fullscreen)
        position = arguments?.getInt(ARG_POSITION, 1) ?: 1
        racesRemaining = arguments?.getInt(ARG_RACES_REMAINING, 0) ?: 0
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
        _binding = DialogRaceCompleteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        playEntranceAnimation()
    }

    private fun setupUI() {
        // Set position display with medal emoji
        val (positionText, medal) = when (position) {
            1 -> "1st Place!" to "🥇"
            2 -> "2nd Place!" to "🥈"
            3 -> "3rd Place!" to "🥉"
            else -> "${position}th Place" to "🏁"
        }
        binding.tvPosition.text = positionText
        binding.tvMedal.text = medal

        // Celebration message based on position
        binding.tvMessage.text = when (position) {
            1 -> "Amazing race! You're a champion!"
            2 -> "Great driving! So close to first!"
            3 -> "Nice work! You made the podium!"
            else -> "Good effort! Keep practicing!"
        }

        // Races remaining
        if (racesRemaining > 0) {
            binding.tvRacesRemaining.text = if (racesRemaining == 1) {
                "You have 1 race left today"
            } else {
                "You have $racesRemaining races left today"
            }
            binding.btnPlayAgain.isEnabled = true
            binding.btnPlayAgain.alpha = 1f
        } else {
            binding.tvRacesRemaining.text = "No races left today - keep reading!"
            binding.btnPlayAgain.isEnabled = false
            binding.btnPlayAgain.alpha = 0.5f
        }

        // Play again button
        binding.btnPlayAgain.setOnClickListener {
            if (racesRemaining > 0) {
                onPlayAgain?.invoke()
                dismiss()
            }
        }

        // Back to reading button
        binding.btnBackToReading.setOnClickListener {
            onBackToReading?.invoke()
            dismiss()
        }

        // Prevent background tap dismissal
        binding.root.setOnClickListener { }
        binding.cardContent.setOnClickListener { }
    }

    private fun playEntranceAnimation() {
        // Initial state
        binding.cardContent.alpha = 0f
        binding.cardContent.scaleX = 0.8f
        binding.cardContent.scaleY = 0.8f
        binding.tvMedal.scaleX = 0f
        binding.tvMedal.scaleY = 0f

        // Card entrance
        val cardFadeIn = ObjectAnimator.ofFloat(binding.cardContent, View.ALPHA, 0f, 1f)
        val cardScaleX = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_X, 0.8f, 1f)
        val cardScaleY = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_Y, 0.8f, 1f)

        val cardAnimSet = AnimatorSet().apply {
            playTogether(cardFadeIn, cardScaleX, cardScaleY)
            duration = 300
            interpolator = OvershootInterpolator(1.2f)
        }

        // Medal pop-in
        val medalScaleX = ObjectAnimator.ofFloat(binding.tvMedal, View.SCALE_X, 0f, 1.3f, 1f)
        val medalScaleY = ObjectAnimator.ofFloat(binding.tvMedal, View.SCALE_Y, 0f, 1.3f, 1f)
        val medalRotate = ObjectAnimator.ofFloat(binding.tvMedal, View.ROTATION, -30f, 10f, 0f)

        val medalAnimSet = AnimatorSet().apply {
            playTogether(medalScaleX, medalScaleY, medalRotate)
            startDelay = 200
            duration = 500
            interpolator = OvershootInterpolator(2f)
        }

        // Position text bounce
        val positionBounce = AnimatorSet().apply {
            val scaleX = ObjectAnimator.ofFloat(binding.tvPosition, View.SCALE_X, 1f, 1.15f, 1f)
            val scaleY = ObjectAnimator.ofFloat(binding.tvPosition, View.SCALE_Y, 1f, 1.15f, 1f)
            playTogether(scaleX, scaleY)
            startDelay = 500
            duration = 400
            interpolator = OvershootInterpolator(2f)
        }

        // Button slide-up
        binding.btnPlayAgain.alpha = 0f
        binding.btnBackToReading.alpha = 0f

        val btnPlaySlide = ObjectAnimator.ofFloat(binding.btnPlayAgain, View.TRANSLATION_Y, 30f, 0f)
        val btnPlayFade = ObjectAnimator.ofFloat(binding.btnPlayAgain, View.ALPHA, 0f, if (racesRemaining > 0) 1f else 0.5f)
        val btnBackSlide = ObjectAnimator.ofFloat(binding.btnBackToReading, View.TRANSLATION_Y, 30f, 0f)
        val btnBackFade = ObjectAnimator.ofFloat(binding.btnBackToReading, View.ALPHA, 0f, 1f)

        val buttonAnimSet = AnimatorSet().apply {
            playTogether(btnPlaySlide, btnPlayFade, btnBackSlide, btnBackFade)
            startDelay = 600
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Play all animations
        AnimatorSet().apply {
            playTogether(cardAnimSet, medalAnimSet, positionBounce, buttonAnimSet)
            start()
        }

        // Confetti for top 3 finishers
        if (position <= 3) {
            Handler(Looper.getMainLooper()).postDelayed({
                binding.confettiView.startConfetti()
            }, 300)
        }
    }

    fun setOnPlayAgainListener(listener: () -> Unit) {
        onPlayAgain = listener
    }

    fun setOnBackToReadingListener(listener: () -> Unit) {
        onBackToReading = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_POSITION = "position"
        private const val ARG_RACES_REMAINING = "races_remaining"

        fun newInstance(position: Int, racesRemaining: Int): RaceCompleteDialog {
            return RaceCompleteDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POSITION, position)
                    putInt(ARG_RACES_REMAINING, racesRemaining)
                }
            }
        }
    }
}
