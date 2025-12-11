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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.arlo.databinding.FragmentUnifiedReaderBinding
import com.example.arlo.tts.TTSService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Unified Reader Fragment that supports both:
 * - Full page mode with ViewPager2 and scrollable text
 * - Sentence mode with large font and navigation buttons
 *
 * Both modes use sentencesJson as the data source.
 */
class UnifiedReaderFragment : Fragment() {

    private var _binding: FragmentUnifiedReaderBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: UnifiedReaderViewModel
    private var bookId: Long = -1L

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
        setupViewPager()
        observeState()

        // Load book
        if (bookId != -1L) {
            viewModel.loadBook(bookId)
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            viewModel.stopReading()
            parentFragmentManager.popBackStack()
        }

        binding.btnModeToggle.setOnClickListener {
            viewModel.toggleMode()
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

        // Page navigation (full page mode)
        binding.btnPrevPage.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current > 0) {
                binding.viewPager.setCurrentItem(current - 1, true)
            }
        }

        binding.btnNextPage.setOnClickListener {
            val adapter = binding.viewPager.adapter as? PageAdapter
            val total = adapter?.itemCount ?: 0
            val current = binding.viewPager.currentItem
            if (current < total - 1) {
                binding.viewPager.setCurrentItem(current + 1, true)
            }
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

    private fun setupViewPager() {
        val adapter = PageAdapter(this, emptyList()) {
            // Add page callback
            if (bookId != -1L) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_ADD_PAGES, bookId))
                    .addToBackStack(null)
                    .commit()
            }
        }
        binding.viewPager.adapter = adapter

        // Page change listener
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.moveToPage(position)
            }
        })
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
        binding.modeFlipper.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.bottomControls.visibility = if (isEmpty) View.GONE else View.VISIBLE

        // Mode-specific UI
        when (state.readerMode) {
            UnifiedReaderViewModel.ReaderState.ReaderMode.FULL_PAGE -> {
                binding.modeFlipper.displayedChild = 0
                binding.btnModeToggle.tooltipText = "Switch to sentence mode"

                // Show page nav, hide sentence nav
                updatePageNavigationButtons(state)
                binding.btnPrevSentence.visibility = View.GONE
                binding.btnNextSentence.visibility = View.GONE

                // Update ViewPager
                val adapter = binding.viewPager.adapter as? PageAdapter
                adapter?.updatePages(state.pages)

                // Sync page position
                if (binding.viewPager.currentItem != state.currentPageIndex && state.pages.isNotEmpty()) {
                    binding.viewPager.setCurrentItem(state.currentPageIndex, false)
                }
            }
            UnifiedReaderViewModel.ReaderState.ReaderMode.SENTENCE -> {
                binding.modeFlipper.displayedChild = 1
                binding.btnModeToggle.tooltipText = "Switch to full page mode"

                // Hide page nav, show sentence nav
                binding.btnPrevPage.visibility = View.GONE
                binding.btnNextPage.visibility = View.GONE
                binding.btnPrevSentence.visibility = View.VISIBLE
                binding.btnNextSentence.visibility = View.VISIBLE

                // Show collaborative button in sentence mode
                binding.btnCollaborative.visibility = View.VISIBLE

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

                    // In collaborative mode, highlight the target words when it's user's turn
                    // Show green when LISTENING (ready for user input), red/green on FEEDBACK
                    // Don't show during IDLE (TTS still playing) even if targetWord is set
                    val showCollaborativeHighlight = state.collaborativeMode && (
                        state.collaborativeState == UnifiedReaderViewModel.CollaborativeState.LISTENING ||
                        state.collaborativeState == UnifiedReaderViewModel.CollaborativeState.FEEDBACK
                    )
                    if (showCollaborativeHighlight && state.targetWord != null) {
                        // Find where the target words appear in the display text
                        // Target words are at the end of the sentence
                        val trimmedText = displayText.trim()
                        val targetWords = state.targetWord
                        val targetStart = trimmedText.length - targetWords.length
                        val targetEnd = trimmedText.length

                        if (targetStart >= 0 && targetStart < targetEnd) {
                            // Determine highlight color based on feedback state
                            // Light green = your turn to read, Red = incorrect
                            val highlightColor = when {
                                state.lastAttemptSuccess == true -> R.color.highlight_success
                                state.lastAttemptSuccess == false -> R.color.error
                                else -> R.color.highlight_success  // Light green for "your turn to read"
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

                    // TTS word highlighting (works in both normal and collaborative modes)
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
            }
        }

        // Hide collaborative button in full page mode
        if (state.readerMode == UnifiedReaderViewModel.ReaderState.ReaderMode.FULL_PAGE) {
            binding.btnCollaborative.visibility = View.GONE
        }

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

    private fun updatePageNavigationButtons(state: UnifiedReaderViewModel.ReaderState) {
        val showNav = state.pages.size > 1
        binding.btnPrevPage.visibility = if (showNav && state.currentPageIndex > 0) View.VISIBLE else View.GONE
        binding.btnNextPage.visibility = if (showNav && state.currentPageIndex < state.pages.size - 1) View.VISIBLE else View.GONE
    }

    private fun updateCollaborativeIndicator(state: UnifiedReaderViewModel.ReaderState) {
        if (!state.collaborativeMode) {
            binding.collaborativeIndicator.visibility = View.GONE
            // Update button appearance
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
                    // Waiting for user to speak
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
                val attemptNum = state.attemptCount + 1  // attemptCount is 0-indexed, display as 1-indexed
                binding.tvCollaborativeStatus.text = if (attemptNum > 1) {
                    "Listening... ($attemptNum of 3)"
                } else {
                    "Listening..."
                }
                binding.ivMicIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), R.color.success)
                )
                // Show and update mic level indicator
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
        // Refresh pages in case background processing completed
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

    /**
     * Toggle collaborative reading mode with permission check.
     */
    private fun toggleCollaborativeMode() {
        val currentState = viewModel.state.value

        if (currentState.collaborativeMode) {
            // Turning off - no permission needed
            viewModel.toggleCollaborativeMode()
        } else {
            // Turning on - check permission first
            if (!viewModel.isSpeechRecognitionAvailable()) {
                // Show diagnostic dialog instead of just a toast
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
        val voices = ttsService.getAvailableVoices()

        // Build voice list with download option at the end
        val voiceNames = voices.map { it.name }.toMutableList()
        voiceNames.add("Download More Voices...")

        val currentVoiceId = ttsService.getCurrentVoiceId()
        val originalVoiceId = currentVoiceId
        var selectedIndex = voices.indexOfFirst { it.id == currentVoiceId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Voice")
            .setSingleChoiceItems(voiceNames.toTypedArray(), selectedIndex) { dialog, which ->
                if (which == voices.size) {
                    // "Download More Voices" selected - open TTS settings
                    dialog.dismiss()
                    openTtsSettings()
                } else {
                    selectedIndex = which
                    val selectedVoice = voices[which]
                    ttsService.setVoice(selectedVoice.id)

                    // Preview the voice at full speed
                    ttsService.stop()
                    ttsService.speakPreview("Hello, I'm your reading assistant.")
                }
            }
            .setPositiveButton("Update") { dialog, _ ->
                // Voice is already set from selection, just dismiss
                ttsService.stop()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // Restore original voice
                ttsService.stop()
                if (originalVoiceId != null) {
                    ttsService.setVoice(originalVoiceId)
                }
                dialog.dismiss()
            }
            .show()
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

        // Create dialog layout programmatically
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null, false).let {
            // Build custom layout
            android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)

                // Current speed label
                val speedLabel = TextView(requireContext()).apply {
                    text = formatSpeedLabel(currentRate)
                    textSize = 18f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 0, 0, 24)
                }
                addView(speedLabel)

                // SeekBar: 0-15 maps to 0.25-1.0 in 0.05 increments
                val seekBar = SeekBar(requireContext()).apply {
                    max = 15  // 16 positions: 0.25, 0.30, ..., 1.0
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

                // Min/max labels
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

                tag = seekBar  // Store seekBar reference
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

    companion object {
        private const val ARG_BOOK_ID = "book_id"

        fun newInstance(bookId: Long) = UnifiedReaderFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_BOOK_ID, bookId)
            }
        }
    }
}
