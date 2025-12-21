package com.example.arlo

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
import com.example.arlo.tts.TTSPreferences
import com.example.arlo.tts.TTSService
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
            binding.sectionInterfaceHeader.isVisible = false
            binding.cardKidMode.isVisible = false
        } else {
            // Parent Mode: Full "Parent Settings" with all options
            binding.tvSettingsTitle.text = "Parent Settings"
            binding.tvSettingsSubtitle.text = "Customize your child's reading experience"

            // Show all sections
            binding.sectionDailyGoalsHeader.isVisible = true
            binding.cardDailyGoals.isVisible = true
            binding.sectionReadingModesHeader.isVisible = true
            binding.cardReadingModes.isVisible = true
            binding.sectionInterfaceHeader.isVisible = true
            binding.cardKidMode.isVisible = true
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
                binding.switchKidMode.isChecked = settings.kidModeEnabled
                binding.switchCollaborative.isChecked = ttsPreferences.getCollaborativeMode()
                binding.switchAutoAdvance.isChecked = ttsPreferences.getAutoAdvance()
            }

            // Load TTS preferences (always available)
            val speechRate = ttsPreferences.getSpeechRate()
            binding.sliderSpeechRate.value = speechRate
            updateSpeedLabel(speechRate)

            // Load available voices
            loadVoices()
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

    private fun saveSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Save parent settings to database (only in parent mode)
            if (!isKidMode) {
                val settings = ParentSettings(
                    dailyPointsTarget = binding.sliderDailyTarget.value.toInt(),
                    enableStreakBonuses = binding.switchStreakBonuses.isChecked,
                    kidModeEnabled = binding.switchKidMode.isChecked,
                    lastModified = System.currentTimeMillis()
                )

                withContext(Dispatchers.IO) {
                    statsRepository.updateParentSettings(settings)
                }

                // Save parent-only TTS preferences
                ttsPreferences.saveCollaborativeMode(binding.switchCollaborative.isChecked)
                ttsPreferences.saveAutoAdvance(binding.switchAutoAdvance.isChecked)
                ttsPreferences.saveKidMode(binding.switchKidMode.isChecked)
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
