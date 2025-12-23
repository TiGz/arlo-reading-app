package com.example.arlo

import android.animation.ObjectAnimator
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.arlo.data.ParentSettings
import com.example.arlo.data.ReadingStatsRepository
import com.example.arlo.databinding.DialogParentSettingsBinding
import com.example.arlo.games.RaceCreditsManager
import com.example.arlo.databinding.ItemMilestoneSliderBinding
import com.example.arlo.tts.TTSPreferences
import com.example.arlo.tts.TTSService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet dialog fragment for settings.
 *
 * In Kid Mode: Shows simplified "Settings" with only Voice & Speed options.
 * In Parent Mode: Shows full "Parent Settings" with all options.
 *
 * Features a modern, playful design with:
 * - Section icons with colored backgrounds
 * - Custom sliders with emoji indicators
 * - Material switches with enhanced touch targets
 * - Prominent save button with coral accent
 */
class ParentSettingsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogParentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var statsRepository: ReadingStatsRepository
    private lateinit var ttsPreferences: TTSPreferences
    private lateinit var ttsService: TTSService

    private var availableVoices: List<TTSService.VoiceInfo> = emptyList()
    private var isKidMode: Boolean = false
    private var isInitialVoiceSelection: Boolean = true
    private var selectedMaxSyllables: Int = 0  // 0 = Any (no limit)
    private var isMilestoneExpanded: Boolean = false
    private var selectedGameDifficulty: String = "BEGINNER"  // BEGINNER, TRAINING, EASY, MEDIUM

    // Milestone slider bindings
    private lateinit var dailyGoalRacesBinding: ItemMilestoneSliderBinding
    private lateinit var multipleRacesBinding: ItemMilestoneSliderBinding
    private lateinit var streakThresholdBinding: ItemMilestoneSliderBinding
    private lateinit var streakRacesBinding: ItemMilestoneSliderBinding
    private lateinit var pageRacesBinding: ItemMilestoneSliderBinding
    private lateinit var chapterRacesBinding: ItemMilestoneSliderBinding
    private lateinit var bookRacesBinding: ItemMilestoneSliderBinding
    private lateinit var completionThresholdBinding: ItemMilestoneSliderBinding

    companion object {
        const val TAG = "ParentSettingsDialog"
        private const val ARG_FORCE_PARENT_MODE = "force_parent_mode"

        fun newInstance(forceParentMode: Boolean = false) = ParentSettingsDialogFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_FORCE_PARENT_MODE, forceParentMode)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheet = (dialogInterface as BottomSheetDialog)
                .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                // Set to expanded state by default
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                // Allow full expansion
                behavior.skipCollapsed = true
                // Set max height to 90% of screen
                val maxHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
                it.layoutParams.height = maxHeight
                // Make background transparent for rounded corners
                it.setBackgroundResource(android.R.color.transparent)
            }
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogParentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as ArloApplication
        statsRepository = app.statsRepository
        ttsPreferences = TTSPreferences(requireContext())
        ttsService = app.ttsService

        // Check if we're in kid mode (secret tap overrides this)
        val forceParentMode = arguments?.getBoolean(ARG_FORCE_PARENT_MODE, false) ?: false
        isKidMode = if (forceParentMode) false else ttsPreferences.getKidMode()

        // Configure UI based on mode
        configureForMode()

        loadSettings()
        setupListeners()
    }

    /**
     * Configure the UI based on whether we're in Kid Mode or Parent Mode.
     */
    private fun configureForMode() {
        if (isKidMode) {
            // Kid Mode: Simple "Settings" with only Voice & Speed
            binding.tvSettingsTitle.text = "Settings"
            binding.tvSettingsSubtitle.text = "Choose your reading voice"

            // Hide parent-only sections
            binding.sectionDailyGoalsHeader.isVisible = false
            binding.cardDailyGoals.isVisible = false
            binding.sectionReadingModesHeader.isVisible = false
            binding.cardReadingModes.isVisible = false
            binding.sectionGameRewardsHeader.isVisible = false
            binding.cardGameRewards.isVisible = false
            binding.sectionDangerousActionsHeader.isVisible = false
            binding.cardDangerousActions.isVisible = false
        } else {
            // Parent Mode: Full "Parent Settings" with all options
            binding.tvSettingsTitle.text = "Parent Settings"
            binding.tvSettingsSubtitle.text = "Customize your child's reading experience"

            // Show all sections
            binding.sectionDailyGoalsHeader.isVisible = true
            binding.cardDailyGoals.isVisible = true
            binding.sectionReadingModesHeader.isVisible = true
            binding.cardReadingModes.isVisible = true
            binding.sectionGameRewardsHeader.isVisible = true
            binding.cardGameRewards.isVisible = true
            binding.sectionDangerousActionsHeader.isVisible = true
            binding.cardDangerousActions.isVisible = true
        }
    }

    private fun loadSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Load parent settings from database (only needed in parent mode)
            if (!isKidMode) {
                val settings = withContext(Dispatchers.IO) {
                    statsRepository.getParentSettings()
                }

                // Set daily points target
                binding.sliderDailyTarget.value = settings.dailyPointsTarget.toFloat()
                binding.tvDailyTargetValue.text = settings.dailyPointsTarget.toString()

                // Set toggles
                binding.switchStreakBonuses.isChecked = settings.enableStreakBonuses
                binding.switchCollaborative.isChecked = ttsPreferences.getCollaborativeMode()
                binding.switchAutoAdvance.isChecked = ttsPreferences.getAutoAdvance()

                // Load max syllables setting
                selectedMaxSyllables = ttsPreferences.getMaxSyllables()
                updateMaxSyllablesSelection(selectedMaxSyllables)

                // Load game reward settings
                binding.switchGameRewards.isChecked = settings.gameRewardsEnabled
                binding.sliderMaxRaces.value = settings.maxRacesPerDay.toFloat()
                binding.tvMaxRacesValue.text = settings.maxRacesPerDay.toString()
                updateMaxRacesVisibility(settings.gameRewardsEnabled)

                // Load game difficulty setting
                selectedGameDifficulty = settings.gameDifficulty
                updateGameDifficultySelection(selectedGameDifficulty)

                // Initialize milestone slider bindings
                initMilestoneSliders(settings)
            }

            // Load TTS preferences (always available)
            val speechRate = ttsPreferences.getSpeechRate()
            binding.sliderSpeechRate.value = speechRate
            updateSpeedLabel(speechRate)

            // Load available voices
            loadVoices()
        }
    }

    private fun initMilestoneSliders(settings: ParentSettings) {
        // Bind included layouts
        dailyGoalRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderDailyGoalRaces.root)
        multipleRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderMultipleRaces.root)
        streakThresholdBinding = ItemMilestoneSliderBinding.bind(binding.sliderStreakThreshold.root)
        streakRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderStreakRaces.root)
        pageRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderPageRaces.root)
        chapterRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderChapterRaces.root)
        bookRacesBinding = ItemMilestoneSliderBinding.bind(binding.sliderBookRaces.root)
        completionThresholdBinding = ItemMilestoneSliderBinding.bind(binding.sliderCompletionThreshold.root)

        // Configure each slider
        setupMilestoneSlider(
            dailyGoalRacesBinding,
            "Daily Goal Races",
            "Races when reaching daily point goal",
            settings.racesForDailyTarget,
            0f, 5f, "races"
        )

        setupMilestoneSlider(
            multipleRacesBinding,
            "Points Multiple Bonus",
            "Races per 2x, 3x, etc. of daily goal",
            settings.racesPerMultiple,
            0f, 5f, "races"
        )

        setupMilestoneSlider(
            streakThresholdBinding,
            "Streak Threshold",
            "Correct answers in a row for bonus",
            settings.streakThreshold,
            3f, 20f, ""
        )

        setupMilestoneSlider(
            streakRacesBinding,
            "Streak Races",
            "Races earned per streak milestone",
            settings.racesPerStreakAchievement,
            0f, 5f, "races"
        )

        setupMilestoneSlider(
            pageRacesBinding,
            "Page Completion",
            "Races when finishing a page",
            settings.racesPerPageCompletion,
            0f, 5f, "races"
        )

        setupMilestoneSlider(
            chapterRacesBinding,
            "Chapter Completion",
            "Races when finishing a chapter",
            settings.racesPerChapterCompletion,
            0f, 5f, "races"
        )

        setupMilestoneSlider(
            bookRacesBinding,
            "Book Completion",
            "Races when finishing a book",
            settings.racesPerBookCompletion,
            0f, 5f, "races"
        )

        // Completion threshold uses percentage (50-100%)
        setupCompletionThresholdSlider(settings.completionThreshold)
    }

    private fun setupMilestoneSlider(
        sliderBinding: ItemMilestoneSliderBinding,
        title: String,
        description: String,
        initialValue: Int,
        minValue: Float,
        maxValue: Float,
        unit: String
    ) {
        sliderBinding.tvMilestoneTitle.text = title
        sliderBinding.tvMilestoneDescription.text = description
        sliderBinding.tvMilestoneUnit.text = unit
        sliderBinding.sliderMilestone.valueFrom = minValue
        sliderBinding.sliderMilestone.valueTo = maxValue
        sliderBinding.sliderMilestone.value = initialValue.toFloat().coerceIn(minValue, maxValue)
        sliderBinding.tvMilestoneValue.text = initialValue.toString()

        sliderBinding.sliderMilestone.addOnChangeListener { _, value, _ ->
            sliderBinding.tvMilestoneValue.text = value.toInt().toString()
        }
    }

    private fun setupCompletionThresholdSlider(initialValue: Float) {
        completionThresholdBinding.tvMilestoneTitle.text = "Completion Threshold"
        completionThresholdBinding.tvMilestoneDescription.text = "Minimum % to earn completion rewards"
        completionThresholdBinding.tvMilestoneUnit.text = "%"
        completionThresholdBinding.sliderMilestone.valueFrom = 50f
        completionThresholdBinding.sliderMilestone.valueTo = 100f
        completionThresholdBinding.sliderMilestone.stepSize = 5f
        val percentValue = (initialValue * 100).toInt()
        completionThresholdBinding.sliderMilestone.value = percentValue.toFloat().coerceIn(50f, 100f)
        completionThresholdBinding.tvMilestoneValue.text = percentValue.toString()

        completionThresholdBinding.sliderMilestone.addOnChangeListener { _, value, _ ->
            completionThresholdBinding.tvMilestoneValue.text = value.toInt().toString()
        }
    }

    private fun loadVoices() {
        // Get available voices from TTS service
        availableVoices = ttsService.getAvailableVoices()

        if (availableVoices.isEmpty()) {
            // Fallback to default Kokoro voices (must match server's available voices)
            availableVoices = listOf(
                TTSService.VoiceInfo("bf_emma", "Emma (British Female)", "en-GB", 400),
                TTSService.VoiceInfo("bf_alice", "Alice (British Female)", "en-GB", 400),
                TTSService.VoiceInfo("bf_lily", "Lily (British Female)", "en-GB", 400),
                TTSService.VoiceInfo("bm_george", "George (British Male)", "en-GB", 400),
                TTSService.VoiceInfo("bm_lewis", "Lewis (British Male)", "en-GB", 400),
                TTSService.VoiceInfo("bm_daniel", "Daniel (British Male)", "en-GB", 400),
                TTSService.VoiceInfo("bm_fable", "Fable (British Male)", "en-GB", 400)
            )
        }

        // Create display names
        val displayNames = availableVoices.map { it.name }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            displayNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerVoice.adapter = adapter

        // Select current voice
        val currentVoice = ttsPreferences.getKokoroVoice()
        val selectedIndex = availableVoices.indexOfFirst { it.id == currentVoice }.takeIf { it >= 0 } ?: 0

        // Reset flag before setting selection
        isInitialVoiceSelection = true
        binding.spinnerVoice.setSelection(selectedIndex)

        // Add listener for voice preview
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Skip preview on initial load
                if (isInitialVoiceSelection) {
                    isInitialVoiceSelection = false
                    return
                }

                // Play preview with the selected voice
                if (position >= 0 && position < availableVoices.size) {
                    val selectedVoice = availableVoices[position]
                    playVoicePreview(selectedVoice.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // No action needed
            }
        }
        // Voice previews are pre-cached on app startup in ArloApplication
    }

    /**
     * Play a preview sentence with the specified voice (at normal speed for voice preview).
     */
    private fun playVoicePreview(voiceId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            ttsService.speakKokoroPreview(ArloApplication.PREVIEW_SENTENCE, voiceId)
        }
    }

    /**
     * Play a preview sentence with the currently selected voice at the current speed setting.
     */
    private fun playSpeedPreview() {
        val selectedPosition = binding.spinnerVoice.selectedItemPosition
        if (selectedPosition >= 0 && selectedPosition < availableVoices.size) {
            val voiceId = availableVoices[selectedPosition].id
            val speed = binding.sliderSpeechRate.value
            viewLifecycleOwner.lifecycleScope.launch {
                ttsService.speakKokoroPreview(ArloApplication.PREVIEW_SENTENCE, voiceId, speed)
            }
        }
    }

    private fun setupListeners() {
        // Daily target slider (only in parent mode)
        if (!isKidMode) {
            binding.sliderDailyTarget.addOnChangeListener { _, value, _ ->
                binding.tvDailyTargetValue.text = value.toInt().toString()
            }

            // Max syllables toggle group
            binding.toggleMaxSyllables.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    selectedMaxSyllables = when (checkedId) {
                        R.id.btnSyllables2 -> 2
                        R.id.btnSyllables3 -> 3
                        R.id.btnSyllables4 -> 4
                        else -> 0  // "Any" = no limit
                    }
                }
            }

            // Game rewards toggle
            binding.switchGameRewards.setOnCheckedChangeListener { _, isChecked ->
                updateMaxRacesVisibility(isChecked)
            }

            // Game difficulty toggle group
            binding.toggleGameDifficulty.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    selectedGameDifficulty = when (checkedId) {
                        R.id.btnDifficultyBeginner -> "BEGINNER"
                        R.id.btnDifficultyTraining -> "TRAINING"
                        R.id.btnDifficultyCasual -> "EASY"
                        R.id.btnDifficultyPro -> "MEDIUM"
                        else -> "BEGINNER"
                    }
                }
            }

            // Max races slider
            binding.sliderMaxRaces.addOnChangeListener { _, value, _ ->
                binding.tvMaxRacesValue.text = value.toInt().toString()
            }

            // Milestone rewards expand/collapse
            binding.headerMilestoneRewards.setOnClickListener {
                toggleMilestoneSection()
            }

            // Reset stats button
            binding.btnResetStats.setOnClickListener {
                showResetConfirmationDialog()
            }
        }

        // Speech rate slider with dynamic label and value
        binding.sliderSpeechRate.addOnChangeListener { _, value, _ ->
            updateSpeedLabel(value)
        }

        // Test speed button
        binding.btnTestSpeed.setOnClickListener {
            playSpeedPreview()
        }

        // Save button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        // Cancel button
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    /**
     * Update the speed label and value based on the current speech rate.
     */
    private fun updateSpeedLabel(rate: Float) {
        val label = when {
            rate < 0.7f -> "Very Slow"
            rate < 0.9f -> "Slow"
            rate < 1.1f -> "Normal"
            rate < 1.3f -> "Fast"
            else -> "Very Fast"
        }
        binding.tvSpeedLabel.text = label
        binding.tvSpeedValue.text = String.format("%.1fx", rate)
    }

    /**
     * Update the max syllables toggle group selection.
     */
    private fun updateMaxSyllablesSelection(maxSyllables: Int) {
        val buttonId = when (maxSyllables) {
            2 -> R.id.btnSyllables2
            3 -> R.id.btnSyllables3
            4 -> R.id.btnSyllables4
            else -> R.id.btnSyllablesAny  // 0 or any other = "Any"
        }
        binding.toggleMaxSyllables.check(buttonId)
    }

    /**
     * Update the game difficulty toggle group selection.
     */
    private fun updateGameDifficultySelection(difficulty: String) {
        val buttonId = when (difficulty) {
            "BEGINNER" -> R.id.btnDifficultyBeginner
            "TRAINING" -> R.id.btnDifficultyTraining
            "EASY" -> R.id.btnDifficultyCasual
            "MEDIUM" -> R.id.btnDifficultyPro
            else -> R.id.btnDifficultyBeginner  // Default to Beginner
        }
        binding.toggleGameDifficulty.check(buttonId)
    }

    /**
     * Show/hide max races slider based on game rewards toggle.
     */
    private fun updateMaxRacesVisibility(enabled: Boolean) {
        binding.layoutMaxRaces.alpha = if (enabled) 1.0f else 0.5f
        binding.sliderMaxRaces.isEnabled = enabled

        // Also control milestone section visibility
        binding.dividerMilestones.alpha = if (enabled) 1.0f else 0.5f
        binding.layoutMilestoneRewards.alpha = if (enabled) 1.0f else 0.5f
        binding.headerMilestoneRewards.isClickable = enabled
    }

    /**
     * Toggle the milestone rewards expandable section.
     */
    private fun toggleMilestoneSection() {
        isMilestoneExpanded = !isMilestoneExpanded

        // Animate chevron rotation
        val rotation = if (isMilestoneExpanded) 180f else 0f
        ObjectAnimator.ofFloat(binding.ivMilestoneExpand, "rotation", rotation).apply {
            duration = 200
            start()
        }

        // Show/hide content
        binding.layoutMilestoneContent.isVisible = isMilestoneExpanded
    }

    /**
     * Show confirmation dialog before resetting all stats.
     */
    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset All Statistics?")
            .setMessage("This will clear all stars, points, streaks, achievements, and reading progress.\n\nYour books and pages will be preserved.\n\nThis action cannot be undone.")
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Reset") { dialog, _ ->
                performStatsReset()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Perform the actual stats reset.
     */
    private fun performStatsReset() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Reset all reading stats
                statsRepository.resetAllStats()

                // Clear race credits SharedPreferences
                RaceCreditsManager.getInstance(requireContext()).clearAll()

                // Delete PixelWheels game data files (unlocked vehicles, lap times, etc.)
                deletePixelWheelsData()
            }

            // Show success feedback
            Snackbar.make(
                binding.root,
                "All statistics have been reset",
                Snackbar.LENGTH_SHORT
            ).show()

            // Dismiss the settings dialog
            dismiss()
        }
    }

    /**
     * Delete PixelWheels game data files (gamestats.json and pixelwheels.conf).
     * This resets vehicle unlocks, lap times, and game preferences.
     */
    private fun deletePixelWheelsData() {
        val context = requireContext()

        // Delete gamestats.json (vehicle unlocks, lap times, race history)
        val gameStatsFile = java.io.File(context.filesDir, "gamestats.json")
        if (gameStatsFile.exists()) {
            val deleted = gameStatsFile.delete()
            android.util.Log.d("ParentSettings", "Deleted gamestats.json: $deleted")
        }

        // Delete pixelwheels.conf (game preferences)
        val configFile = java.io.File(context.filesDir, "pixelwheels.conf")
        if (configFile.exists()) {
            val deleted = configFile.delete()
            android.util.Log.d("ParentSettings", "Deleted pixelwheels.conf: $deleted")
        }

        // Also clear any PixelWheels SharedPreferences
        val pwPrefs = context.getSharedPreferences("pixelwheels", android.content.Context.MODE_PRIVATE)
        pwPrefs.edit().clear().apply()
        android.util.Log.d("ParentSettings", "Cleared PixelWheels SharedPreferences")
    }

    private fun saveSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Save parent settings to database (only in parent mode)
            if (!isKidMode) {
                val settings = ParentSettings(
                    dailyPointsTarget = binding.sliderDailyTarget.value.toInt(),
                    enableStreakBonuses = binding.switchStreakBonuses.isChecked,
                    kidModeEnabled = ttsPreferences.getKidMode(),  // Preserve existing kid mode setting
                    gameRewardsEnabled = binding.switchGameRewards.isChecked,
                    maxRacesPerDay = binding.sliderMaxRaces.value.toInt(),
                    gameDifficulty = selectedGameDifficulty,
                    // Milestone settings
                    racesForDailyTarget = dailyGoalRacesBinding.sliderMilestone.value.toInt(),
                    racesPerMultiple = multipleRacesBinding.sliderMilestone.value.toInt(),
                    streakThreshold = streakThresholdBinding.sliderMilestone.value.toInt(),
                    racesPerStreakAchievement = streakRacesBinding.sliderMilestone.value.toInt(),
                    racesPerPageCompletion = pageRacesBinding.sliderMilestone.value.toInt(),
                    racesPerChapterCompletion = chapterRacesBinding.sliderMilestone.value.toInt(),
                    racesPerBookCompletion = bookRacesBinding.sliderMilestone.value.toInt(),
                    completionThreshold = completionThresholdBinding.sliderMilestone.value / 100f,
                    lastModified = System.currentTimeMillis()
                )

                withContext(Dispatchers.IO) {
                    statsRepository.updateParentSettings(settings)
                }

                // Save parent-only TTS preferences
                ttsPreferences.saveCollaborativeMode(binding.switchCollaborative.isChecked)
                ttsPreferences.saveAutoAdvance(binding.switchAutoAdvance.isChecked)
                ttsPreferences.saveMaxSyllables(selectedMaxSyllables)
            }

            // Save TTS preferences (always available - voice and speed)
            ttsPreferences.saveSpeechRate(binding.sliderSpeechRate.value)

            // Save selected voice
            val selectedPosition = binding.spinnerVoice.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < availableVoices.size) {
                ttsPreferences.saveKokoroVoice(availableVoices[selectedPosition].id)
            }

            // Dismiss dialog
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
