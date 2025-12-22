package com.example.arlo.games

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.arlo.ArloApplication
import com.example.arlo.data.SentenceCompletionState
import com.example.arlo.databinding.DialogMissedStarsBinding
import com.example.arlo.databinding.ItemMissedStarBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog showing missed stars (sentences that were skipped by TTS after max retries).
 * Allows the user to tap a sentence to navigate back and retry it.
 */
class MissedStarsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogMissedStarsBinding? = null
    private val binding get() = _binding!!

    private var missedStars: List<MissedStarItem> = emptyList()
    private var onStarClickListener: ((Long, Long, Int) -> Unit)? = null

    companion object {
        const val TAG = "MissedStarsDialog"
        private const val ARG_BOOK_ID = "book_id"

        fun newInstance(bookId: Long, onStarClick: (pageId: Long, bookId: Long, sentenceIndex: Int) -> Unit): MissedStarsDialogFragment {
            return MissedStarsDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BOOK_ID, bookId)
                }
                onStarClickListener = onStarClick
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogMissedStarsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configure bottom sheet
        (dialog as? BottomSheetDialog)?.let { bottomSheetDialog ->
            bottomSheetDialog.setOnShowListener {
                val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
                bottomSheet?.let {
                    val behavior = BottomSheetBehavior.from(it)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                }
            }
        }

        // Setup RecyclerView
        binding.recyclerMissedStars.layoutManager = LinearLayoutManager(requireContext())

        // Load missed stars
        val bookId = arguments?.getLong(ARG_BOOK_ID) ?: 0L
        loadMissedStars(bookId)

        // Close button
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun loadMissedStars(bookId: Long) {
        val app = requireActivity().application as ArloApplication

        CoroutineScope(Dispatchers.IO).launch {
            val states = app.statsRepository.getMissedStars(bookId)
            val pages = app.repository.getPagesSync(bookId)
            val pageMap = pages.associateBy { it.id }

            missedStars = states.mapNotNull { state ->
                val page = pageMap[state.pageId] ?: return@mapNotNull null
                val sentences = page.sentencesJson?.let { json ->
                    try {
                        com.google.gson.Gson().fromJson(
                            json,
                            Array<com.example.arlo.data.SentenceData>::class.java
                        ).toList()
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@mapNotNull null

                val sentence = sentences.getOrNull(state.sentenceIndex) ?: return@mapNotNull null

                MissedStarItem(
                    state = state,
                    pageNumber = page.pageNumber,
                    sentenceText = sentence.text,
                    chapterTitle = page.resolvedChapter
                )
            }

            withContext(Dispatchers.Main) {
                if (missedStars.isEmpty()) {
                    binding.tvEmptyMessage.visibility = View.VISIBLE
                    binding.recyclerMissedStars.visibility = View.GONE
                } else {
                    binding.tvEmptyMessage.visibility = View.GONE
                    binding.recyclerMissedStars.visibility = View.VISIBLE
                    binding.recyclerMissedStars.adapter = MissedStarsAdapter(missedStars) { item ->
                        onStarClickListener?.invoke(item.state.pageId, item.state.bookId, item.state.sentenceIndex)
                        dismiss()
                    }
                }

                // Update count in header
                binding.tvMissedCount.text = "${missedStars.size} missed"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Data class for displaying a missed star in the list.
 */
data class MissedStarItem(
    val state: SentenceCompletionState,
    val pageNumber: Int,
    val sentenceText: String,
    val chapterTitle: String?
)

/**
 * Adapter for the missed stars RecyclerView.
 */
class MissedStarsAdapter(
    private val items: List<MissedStarItem>,
    private val onClick: (MissedStarItem) -> Unit
) : RecyclerView.Adapter<MissedStarsAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemMissedStarBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMissedStarBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        with(holder.binding) {
            tvPageNumber.text = "Page ${item.pageNumber}"
            tvSentence.text = item.sentenceText
            tvChapter.text = item.chapterTitle ?: ""
            tvChapter.visibility = if (item.chapterTitle != null) View.VISIBLE else View.GONE

            root.setOnClickListener {
                onClick(item)
            }
        }
    }

    override fun getItemCount() = items.size
}
