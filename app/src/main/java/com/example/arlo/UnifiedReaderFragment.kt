package com.example.arlo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arlo.databinding.FragmentUnifiedReaderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Sentence-by-sentence reader fragment with collaborative reading mode.
 * Displays one sentence at a time with large fonts (28sp).
 */
class UnifiedReaderFragment : Fragment() {

    private var _binding: FragmentUnifiedReaderBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: UnifiedReaderViewModel
    private var bookId: Long = -1L

    // Secret tap counter for parental unlock (5 taps on book title)
    private var secretTapCount = 0
    private var lastSecretTapTime = 0L
    private val SECRET_TAP_TIMEOUT_MS = 2000L  // Reset if more than 2s between taps
    private val SECRET_TAP_COUNT_REQUIRED = 5

    // Permission launcher for RECORD_AUDIO
    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleCollaborativeMode()
        } else {
            Toast.makeText(
                requireContext(),
                "Microphone permission is required for collaborative reading",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            bookId = it.getLong(ARG_BOOK_ID, -1L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUnifiedReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[UnifiedReaderViewModel::class.java]

        setupUI()
        observeState()

        // Load book (check audio permission for collaborative mode)
        if (bookId != -1L) {
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            viewModel.loadBook(bookId, hasAudioPermission)
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            viewModel.stopReading()
            parentFragmentManager.popBackStack()
        }

        // Secret tap on book title to toggle kid mode (5 taps within 2 seconds)
        binding.tvBookTitle.setOnClickListener {
            handleSecretTap()
        }

        binding.btnAutoAdvance.setOnClickListener {
            viewModel.toggleAutoAdvance()
        }

        binding.btnCollaborative.setOnClickListener {
            toggleCollaborativeMode()
        }

        binding.btnVoice.setOnClickListener {
            showVoicePicker()
        }

        binding.btnSpeed.setOnClickListener {
            showSpeedPicker()
        }

        binding.btnAddPage.setOnClickListener {
            viewModel.stopReading()
            if (bookId != -1L) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_ADD_PAGES, bookId))
                    .addToBackStack(null)
                    .commit()
            }
        }

        // Sentence navigation
        binding.btnPrevSentence.setOnClickListener {
            viewModel.previousSentence()
        }

        binding.btnNextSentence.setOnClickListener {
            viewModel.nextSentence()
        }

        // TTS controls
        binding.btnPlayPause.setOnClickListener {
            viewModel.togglePlayPause()
        }

        // Need more pages
        binding.btnScanMorePages.setOnClickListener {
            viewModel.stopReading()
            if (bookId != -1L) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_ADD_PAGES, bookId))
                    .addToBackStack(null)
                    .commit()
            }
        }

        // Empty state capture
        binding.btnCaptureFirst.setOnClickListener {
            if (bookId != -1L) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_ADD_PAGES, bookId))
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: UnifiedReaderViewModel.ReaderState) {
        // Loading
        binding.loadingOverlay.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Book title
        state.book?.let { book ->
            binding.tvBookTitle.text = book.title
        }

        // Page indicator
        if (state.totalPages > 0) {
            binding.tvPageIndicator.text = "Page ${state.pageNumber} of ${state.totalPages}"
        } else {
            binding.tvPageIndicator.text = "No pages"
        }

        // Processing banner
        if (state.pendingPageCount > 0) {
            binding.processingBanner.visibility = View.VISIBLE
            binding.tvProcessingStatus.text = if (state.pendingPageCount == 1) {
                "1 page processing..."
            } else {
                "${state.pendingPageCount} pages processing..."
            }
        } else {
            binding.processingBanner.visibility = View.GONE
        }

        // Empty state
        val isEmpty = state.pages.isEmpty() && !state.isLoading
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.sentenceScrollView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.bottomControls.visibility = if (isEmpty) View.GONE else View.VISIBLE

        // Sentence display
        val sentence = state.currentSentence
        if (sentence != null) {
            val displayText = if (state.isLastSentenceIncomplete) {
                "${sentence.text}..."
            } else {
                sentence.text
            }

            // Apply highlighting based on mode
            val spannable = SpannableString(displayText)
            var hasHighlight = false

            // In collaborative mode, highlight the target words
            if (state.collaborativeMode && state.targetWord != null) {
                val trimmedText = displayText.trim()
                val targetWords = state.targetWord
                val targetStart = trimmedText.length - targetWords.length
                val targetEnd = trimmedText.length

                val showHighlight = state.isSpeakingTargetWord ||
                    state.collaborativeState == UnifiedReaderViewModel.CollaborativeState.LISTENING ||
                    state.collaborativeState == UnifiedReaderViewModel.CollaborativeState.FEEDBACK

                if (showHighlight && targetStart >= 0 && targetStart < targetEnd) {
                    val highlightColor = when {
                        state.isSpeakingTargetWord -> R.color.highlight_word  // Yellow
                        state.lastAttemptSuccess == true -> R.color.highlight_success  // Green
                        state.lastAttemptSuccess == false -> R.color.error  // Red
                        else -> R.color.highlight_success  // Green for "your turn to read"
                    }
                    spannable.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(requireContext(), highlightColor)),
                        targetStart,
                        targetEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    hasHighlight = true
                }
            }

            // TTS word highlighting
            if (state.highlightRange != null && state.isPlaying) {
                val (start, end) = state.highlightRange
                if (start >= 0 && end <= displayText.length && start < end) {
                    spannable.setSpan(
                        BackgroundColorSpan(ContextCompat.getColor(requireContext(), R.color.highlight_word)),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    hasHighlight = true
                }
            }

            binding.tvSentence.text = if (hasHighlight) spannable else displayText

            // Orange tint for incomplete sentences
            val textColor = if (state.isLastSentenceIncomplete) {
                ContextCompat.getColor(requireContext(), R.color.warning)
            } else {
                ContextCompat.getColor(requireContext(), R.color.reader_text_primary)
            }
            binding.tvSentence.setTextColor(textColor)
        } else if (!state.isLoading && state.pages.isNotEmpty()) {
            binding.tvSentence.text = "No text on this page"
            binding.tvSentence.setTextColor(ContextCompat.getColor(requireContext(), R.color.reader_text_secondary))
        }

        // Hide collaborative toggle in kid mode (locked ON)
        binding.btnCollaborative.visibility = if (state.kidMode) View.GONE else View.VISIBLE

        // Collaborative mode indicator
        updateCollaborativeIndicator(state)

        // Sentence indicator
        if (state.sentences.isNotEmpty()) {
            binding.tvSentenceIndicator.text = "Sentence ${state.sentenceNumber} of ${state.totalSentences}"
            binding.tvSentenceIndicator.visibility = View.VISIBLE
        } else {
            binding.tvSentenceIndicator.visibility = View.GONE
        }

        // Navigation button states
        val canGoPrev = state.currentSentenceIndex > 0 || state.currentPageIndex > 0
        val canGoNext = !state.needsMorePages && (
            state.currentSentenceIndex < state.sentences.size - 1 ||
            state.currentPageIndex < state.pages.size - 1
        )
        binding.btnPrevSentence.alpha = if (canGoPrev) 1f else 0.3f
        binding.btnNextSentence.alpha = if (canGoNext) 1f else 0.3f

        // Need more pages banner
        binding.needMorePagesBanner.visibility = if (state.needsMorePages) View.VISIBLE else View.GONE

        // Play/pause button
        val playPauseIcon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(playPauseIcon)
        binding.btnPlayPause.contentDescription = if (state.isPlaying) "Pause reading" else "Play reading"

        // Auto-advance button
        val autoAdvanceIcon = if (state.autoAdvance) R.drawable.ic_repeat else R.drawable.ic_repeat_one
        binding.btnAutoAdvance.setImageResource(autoAdvanceIcon)
        binding.btnAutoAdvance.tooltipText = if (state.autoAdvance) "Auto-advance: ON" else "Auto-advance: OFF (manual)"
        binding.btnAutoAdvance.contentDescription = if (state.autoAdvance) "Auto-advance is on, tap to switch to manual" else "Manual mode, tap to switch to auto-advance"
    }

    private fun updateCollaborativeIndicator(state: UnifiedReaderViewModel.ReaderState) {
        if (!state.collaborativeMode) {
            binding.collaborativeIndicator.visibility = View.GONE
            binding.btnCollaborative.tooltipText = "Collaborative reading: OFF"
            binding.btnCollaborative.alpha = 0.6f
            return
        }

        // Update button appearance when active
        binding.btnCollaborative.tooltipText = "Collaborative reading: ON"
        binding.btnCollaborative.alpha = 1.0f

        // Show indicator based on collaborative state
        when (state.collaborativeState) {
            UnifiedReaderViewModel.CollaborativeState.IDLE -> {
                binding.micLevelIndicator.visibility = View.GONE
                if (state.targetWord != null) {
                    binding.collaborativeIndicator.visibility = View.VISIBLE
                    binding.tvCollaborativeStatus.text = "Your turn!"
                    binding.ivMicIndicator.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.primary)
                    )
                } else {
                    binding.collaborativeIndicator.visibility = View.GONE
                }
            }
            UnifiedReaderViewModel.CollaborativeState.LISTENING -> {
                binding.collaborativeIndicator.visibility = View.VISIBLE
                val attemptNum = state.attemptCount + 1
                binding.tvCollaborativeStatus.text = if (attemptNum > 1) {
                    "Listening... ($attemptNum of 3)"
                } else {
                    "Listening..."
                }
                binding.ivMicIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.success)
                )
                binding.micLevelIndicator.visibility = View.VISIBLE
                binding.micLevelIndicator.progress = state.micLevel
            }
            UnifiedReaderViewModel.CollaborativeState.FEEDBACK -> {
                binding.collaborativeIndicator.visibility = View.VISIBLE
                binding.micLevelIndicator.visibility = View.GONE
                if (state.lastAttemptSuccess == true) {
                    binding.tvCollaborativeStatus.text = "Correct!"
                    binding.ivMicIndicator.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.success)
                    )
                } else {
                    val attemptsLeft = 3 - state.attemptCount
                    binding.tvCollaborativeStatus.text = if (attemptsLeft > 0) {
                        "Try again ($attemptsLeft left)"
                    } else {
                        "Listen..."
                    }
                    binding.ivMicIndicator.setColorFilter(
                        ContextCompat.getColor(requireContext(), R.color.error)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPages()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopReading()
        viewModel.cancelSpeechRecognition()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopReading()
        viewModel.cancelSpeechRecognition()
        _binding = null
    }

    private fun toggleCollaborativeMode() {
        val currentState = viewModel.state.value

        if (currentState.collaborativeMode) {
            viewModel.toggleCollaborativeMode()
        } else {
            if (!viewModel.isSpeechRecognitionAvailable()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Speech Recognition Unavailable")
                    .setMessage(
                        "Speech recognition is not available on this device.\n\n" +
                        "Diagnostics:\n${viewModel.speechDiagnostics}\n\n" +
                        "For Fire tablets, you may need to install Google Play Services."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> {
                    viewModel.toggleCollaborativeMode()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Microphone Permission Needed")
                        .setMessage("Arlo needs microphone access to hear you read words aloud. This helps you practice reading!")
                        .setPositiveButton("Grant Permission") { _, _ ->
                            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                else -> {
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    private fun showVoicePicker() {
        val ttsService = viewModel.ttsService

        lifecycleScope.launch {
            val kokoroVoices = ttsService.getKokoroVoices()
            val androidVoices = ttsService.getAvailableVoices()

            data class VoiceItem(val id: String, val displayName: String, val isKokoro: Boolean)

            val allVoices = mutableListOf<VoiceItem>()

            kokoroVoices.forEach { voiceId ->
                allVoices.add(VoiceItem(voiceId, "Kokoro: $voiceId", isKokoro = true))
            }

            androidVoices.forEach { voice ->
                allVoices.add(VoiceItem(voice.id, voice.name, isKokoro = false))
            }

            val voiceNames = allVoices.map { it.displayName }.toMutableList()
            voiceNames.add("Download More Voices...")

            val currentKokoroVoice = ttsService.getKokoroVoice()
            val currentAndroidVoiceId = ttsService.getCurrentVoiceId()
            val originalKokoroVoice = currentKokoroVoice
            val originalAndroidVoice = currentAndroidVoiceId

            var selectedIndex = allVoices.indexOfFirst { it.isKokoro && it.id == currentKokoroVoice }
            if (selectedIndex < 0) {
                selectedIndex = allVoices.indexOfFirst { !it.isKokoro && it.id == currentAndroidVoiceId }
            }
            selectedIndex = selectedIndex.coerceAtLeast(0)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Voice")
                .setSingleChoiceItems(voiceNames.toTypedArray(), selectedIndex) { dialog, which ->
                    if (which == allVoices.size) {
                        dialog.dismiss()
                        openTtsSettings()
                    } else {
                        selectedIndex = which
                        val selectedVoice = allVoices[which]

                        ttsService.stop()
                        if (selectedVoice.isKokoro) {
                            lifecycleScope.launch {
                                ttsService.speakKokoroPreview("Hello, I'm your reading assistant.", selectedVoice.id)
                            }
                        } else {
                            ttsService.setVoice(selectedVoice.id)
                            ttsService.speakPreview("Hello, I'm your reading assistant.")
                        }
                    }
                }
                .setPositiveButton("Update") { dialog, _ ->
                    ttsService.stop()
                    if (selectedIndex < allVoices.size) {
                        val selectedVoice = allVoices[selectedIndex]
                        if (selectedVoice.isKokoro) {
                            viewModel.setKokoroVoice(selectedVoice.id)
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    ttsService.stop()
                    ttsService.setKokoroVoice(originalKokoroVoice)
                    if (originalAndroidVoice != null) {
                        ttsService.setVoice(originalAndroidVoice)
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSpeedPicker() {
        val currentRate = viewModel.state.value.speechRate

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null, false).let {
            android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)

                val speedLabel = TextView(requireContext()).apply {
                    text = formatSpeedLabel(currentRate)
                    textSize = 18f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 0, 0, 24)
                }
                addView(speedLabel)

                val seekBar = SeekBar(requireContext()).apply {
                    max = 15
                    progress = ((currentRate - 0.25f) / 0.05f).toInt().coerceIn(0, 15)

                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val rate = 0.25f + (progress * 0.05f)
                            speedLabel.text = formatSpeedLabel(rate)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
                addView(seekBar)

                val labelsLayout = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)

                    val minLabel = TextView(requireContext()).apply {
                        text = "0.25x"
                        textSize = 12f
                    }
                    addView(minLabel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    val maxLabel = TextView(requireContext()).apply {
                        text = "1.0x"
                        textSize = 12f
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    }
                    addView(maxLabel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                addView(labelsLayout)

                tag = seekBar
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reading Speed")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val seekBar = dialogView.tag as SeekBar
                val rate = 0.25f + (seekBar.progress * 0.05f)
                viewModel.setSpeechRate(rate)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatSpeedLabel(rate: Float): String {
        return String.format("%.2fx", rate)
    }

    /**
     * Handle secret tap on book title to toggle kid mode.
     * Requires 5 taps within 2 seconds to toggle.
     */
    private fun handleSecretTap() {
        val now = System.currentTimeMillis()

        // Reset if too much time has passed since last tap
        if (now - lastSecretTapTime > SECRET_TAP_TIMEOUT_MS) {
            secretTapCount = 0
        }

        secretTapCount++
        lastSecretTapTime = now

        if (secretTapCount >= SECRET_TAP_COUNT_REQUIRED) {
            secretTapCount = 0
            val wasKidMode = viewModel.state.value.kidMode
            viewModel.toggleKidMode()

            // Show toast feedback for parent
            val message = if (wasKidMode) {
                "Parent mode unlocked"
            } else {
                "Kid mode enabled"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_BOOK_ID = "book_id"

        fun newInstance(bookId: Long) = UnifiedReaderFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_BOOK_ID, bookId)
            }
        }
    }
}
