package com.example.arlo.games

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.example.arlo.R
import com.example.arlo.databinding.DialogRaceRewardsBinding

/**
 * Dialog showing available race rewards and allowing the user to start racing.
 * Displayed when tapping the games icon in the reader toolbar.
 */
class RaceRewardsDialogFragment : DialogFragment() {

    private var _binding: DialogRaceRewardsBinding? = null
    private val binding get() = _binding!!

    private var availableRaces: Int = 0
    private var onStartRacing: ((Int) -> Unit)? = null
    private var onContinueReading: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Arlo_Dialog_Fullscreen)
        availableRaces = arguments?.getInt(ARG_AVAILABLE_RACES, 0) ?: 0
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
        _binding = DialogRaceRewardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        playEntranceAnimation()
    }

    private fun setupUI() {
        if (availableRaces > 0) {
            // Has races available
            binding.tvRaceCount.text = availableRaces.toString()
            binding.tvRaceLabel.text = if (availableRaces == 1) "race\nto go!" else "races\nto go!"
            binding.tvSubtitle.text = "You earned these by reading today!"

            binding.btnStartRacing.isVisible = true
            binding.btnContinueReading.isVisible = true
            binding.noRacesContainer.isVisible = false
        } else {
            // No races available
            binding.tvRaceCount.text = "0"
            binding.tvRaceLabel.text = "races\navailable"
            binding.tvSubtitle.text = "Keep reading to earn races!"

            binding.btnStartRacing.isVisible = false
            binding.btnContinueReading.isVisible = false
            binding.noRacesContainer.isVisible = true
        }

        binding.btnStartRacing.setOnClickListener {
            onStartRacing?.invoke(availableRaces)
            dismiss()
        }

        binding.btnContinueReading.setOnClickListener {
            onContinueReading?.invoke()
            dismiss()
        }

        binding.btnNoRacesContinue.setOnClickListener {
            onContinueReading?.invoke()
            dismiss()
        }

        // Dismiss on background tap
        binding.root.setOnClickListener {
            dismiss()
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
        binding.ivSteeringWheel.rotation = -180f

        // Card entrance with pop
        val cardFadeIn = ObjectAnimator.ofFloat(binding.cardContent, View.ALPHA, 0f, 1f)
        val cardScaleX = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_X, 0.7f, 1.05f, 1f)
        val cardScaleY = ObjectAnimator.ofFloat(binding.cardContent, View.SCALE_Y, 0.7f, 1.05f, 1f)

        val cardAnimSet = AnimatorSet().apply {
            playTogether(cardFadeIn, cardScaleX, cardScaleY)
            duration = 350
            interpolator = OvershootInterpolator(1.2f)
        }

        // Steering wheel spin
        val wheelSpin = ObjectAnimator.ofFloat(binding.ivSteeringWheel, View.ROTATION, -180f, 0f).apply {
            duration = 600
            startDelay = 150
            interpolator = OvershootInterpolator(1.5f)
        }

        // Race count bounce
        val countBounce = AnimatorSet().apply {
            val scaleUpX = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_X, 0.5f, 1.2f, 1f)
            val scaleUpY = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_Y, 0.5f, 1.2f, 1f)
            playTogether(scaleUpX, scaleUpY)
            startDelay = 300
            duration = 400
            interpolator = OvershootInterpolator(2f)
        }

        AnimatorSet().apply {
            playTogether(cardAnimSet, wheelSpin, countBounce)
            start()
        }
    }

    /**
     * Plays a celebration animation when the dialog is shown after earning a new race.
     */
    fun playCelebration() {
        val pulseX = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_X, 1f, 1.3f, 1f)
        val pulseY = ObjectAnimator.ofFloat(binding.tvRaceCount, View.SCALE_Y, 1f, 1.3f, 1f)
        val wheelSpin = ObjectAnimator.ofFloat(binding.ivSteeringWheel, View.ROTATION, 0f, 360f)

        AnimatorSet().apply {
            playTogether(pulseX, pulseY, wheelSpin)
            duration = 500
            interpolator = OvershootInterpolator(1.5f)
            start()
        }
    }

    fun setOnStartRacingListener(listener: (Int) -> Unit) {
        onStartRacing = listener
    }

    fun setOnContinueReadingListener(listener: () -> Unit) {
        onContinueReading = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RaceRewardsDialog"
        private const val ARG_AVAILABLE_RACES = "available_races"

        fun newInstance(availableRaces: Int): RaceRewardsDialogFragment {
            return RaceRewardsDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_AVAILABLE_RACES, availableRaces)
                }
            }
        }
    }
}
