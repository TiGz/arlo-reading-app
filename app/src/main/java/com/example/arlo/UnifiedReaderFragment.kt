package com.example.arlo

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        binding.btnVoice.setOnClickListener {
            showVoicePicker()
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

                // Sentence display
                val sentence = state.currentSentence
                if (sentence != null) {
                    val displayText = if (state.isLastSentenceIncomplete) {
                        "${sentence.text}..."
                    } else {
                        sentence.text
                    }

                    // Apply word highlighting if available
                    val highlightRange = state.highlightRange
                    if (highlightRange != null && state.isPlaying) {
                        val (start, end) = highlightRange
                        if (start >= 0 && end <= displayText.length && start < end) {
                            val spannable = SpannableString(displayText)
                            spannable.setSpan(
                                BackgroundColorSpan(ContextCompat.getColor(requireContext(), R.color.highlight_word)),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            binding.tvSentence.text = spannable
                        } else {
                            binding.tvSentence.text = displayText
                        }
                    } else {
                        binding.tvSentence.text = displayText
                    }

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

    override fun onResume() {
        super.onResume()
        // Refresh pages in case background processing completed
        viewModel.refreshPages()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopReading()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopReading()
        _binding = null
    }

    private fun showVoicePicker() {
        val ttsService = viewModel.ttsService
        val voices = ttsService.getAvailableVoices()

        if (voices.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Voices Available")
                .setMessage("No text-to-speech voices are installed on this device.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val currentVoiceId = ttsService.getCurrentVoiceId()
        val voiceNames = voices.map { "${it.name} (${it.locale})" }.toTypedArray()
        val selectedIndex = voices.indexOfFirst { it.id == currentVoiceId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Voice")
            .setSingleChoiceItems(voiceNames, selectedIndex) { dialog, which ->
                val selectedVoice = voices[which]
                ttsService.setVoice(selectedVoice.id)

                // Preview the voice with a sample
                ttsService.stop()
                ttsService.speak("Hello, I'm your reading assistant.")

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
