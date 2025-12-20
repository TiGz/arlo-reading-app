package com.example.arlo

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.arlo.data.ParentSettings
import com.example.arlo.data.ReadingStatsRepository
import com.example.arlo.databinding.DialogParentSettingsBinding
import com.example.arlo.tts.TTSPreferences
import com.example.arlo.tts.TTSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog fragment for parent settings.
 * Triggered by long-press on settings button or 5-tap gesture.
 */
class ParentSettingsDialogFragment : DialogFragment() {

    private var _binding: DialogParentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var statsRepository: ReadingStatsRepository
    private lateinit var ttsPreferences: TTSPreferences
    private lateinit var ttsService: TTSService

    private var availableVoices: List<TTSService.VoiceInfo> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
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

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Load parent settings from database
            val settings = withContext(Dispatchers.IO) {
                statsRepository.getParentSettings()
            }

            // Set daily points target
            binding.sliderDailyTarget.value = settings.dailyPointsTarget.toFloat()
            binding.tvDailyTargetValue.text = settings.dailyPointsTarget.toString()

            // Set toggles
            binding.switchStreakBonuses.isChecked = settings.enableStreakBonuses
            binding.switchKidMode.isChecked = settings.kidModeEnabled

            // Load TTS preferences
            binding.sliderSpeechRate.value = ttsPreferences.getSpeechRate()
            binding.switchCollaborative.isChecked = ttsPreferences.getCollaborativeMode()

            // Load available voices
            loadVoices()
        }
    }

    private fun loadVoices() {
        // Get available voices from TTS service
        availableVoices = ttsService.getAvailableVoices()

        if (availableVoices.isEmpty()) {
            // Fallback to default Kokoro voices
            availableVoices = listOf(
                TTSService.VoiceInfo("bf_emma", "Emma (British Female)", "en-GB", 400),
                TTSService.VoiceInfo("bf_isabella", "Isabella (British Female)", "en-GB", 400),
                TTSService.VoiceInfo("bm_lewis", "Lewis (British Male)", "en-GB", 400),
                TTSService.VoiceInfo("bm_george", "George (British Male)", "en-GB", 400)
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
        binding.spinnerVoice.setSelection(selectedIndex)
    }

    private fun setupListeners() {
        // Daily target slider
        binding.sliderDailyTarget.addOnChangeListener { _, value, _ ->
            binding.tvDailyTargetValue.text = value.toInt().toString()
        }

        // Save button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Save parent settings to database
            val settings = ParentSettings(
                dailyPointsTarget = binding.sliderDailyTarget.value.toInt(),
                enableStreakBonuses = binding.switchStreakBonuses.isChecked,
                kidModeEnabled = binding.switchKidMode.isChecked,
                lastModified = System.currentTimeMillis()
            )

            withContext(Dispatchers.IO) {
                statsRepository.updateParentSettings(settings)
            }

            // Save TTS preferences
            ttsPreferences.saveSpeechRate(binding.sliderSpeechRate.value)
            ttsPreferences.saveCollaborativeMode(binding.switchCollaborative.isChecked)
            ttsPreferences.saveKidMode(binding.switchKidMode.isChecked)

            // Save selected voice
            val selectedPosition = binding.spinnerVoice.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < availableVoices.size) {
                ttsPreferences.saveKokoroVoice(availableVoices[selectedPosition].id)
            }

            // Dismiss dialog
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Set dialog to 90% of screen width
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ParentSettingsDialog"

        fun newInstance() = ParentSettingsDialogFragment()
    }
}
