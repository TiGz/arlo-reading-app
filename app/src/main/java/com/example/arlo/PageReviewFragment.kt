package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.arlo.data.Page
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PageReviewFragment : Fragment() {

    private val viewModel: PageReviewViewModel by viewModels()
    private lateinit var adapter: PageReviewAdapter

    private lateinit var viewPager: ViewPager2
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var btnRecapture: MaterialButton
    private lateinit var emptyState: LinearLayout
    private lateinit var bottomBar: LinearLayout

    private var pages: List<Page> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_page_review, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get book ID from arguments
        val bookId = arguments?.getLong(ARG_BOOK_ID, -1L) ?: -1L
        if (bookId == -1L) {
            parentFragmentManager.popBackStack()
            return
        }
        viewModel.setBookId(bookId)

        // Initialize views
        viewPager = view.findViewById(R.id.viewPager)
        tvPageIndicator = view.findViewById(R.id.tvPageIndicator)
        tvConfidence = view.findViewById(R.id.tvConfidence)
        btnRecapture = view.findViewById(R.id.btnRecapture)
        emptyState = view.findViewById(R.id.emptyState)
        bottomBar = view.findViewById(R.id.bottomBar)

        val btnBack: ImageButton = view.findViewById(R.id.btnBack)
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Setup ViewPager
        adapter = PageReviewAdapter()
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.setCurrentPageIndex(position)
                updatePageIndicator(position)
                updateConfidenceDisplay(position)
                updateRecaptureButton(position)
            }
        })

        // Recapture button
        btnRecapture.setOnClickListener {
            val currentPage = adapter.getPageAt(viewPager.currentItem)
            if (currentPage != null) {
                navigateToCameraForRecapture(currentPage)
            }
        }

        // Observe pages
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getPagesForBook().collectLatest { pageList ->
                    pages = pageList
                    adapter.submitList(pageList)

                    if (pageList.isEmpty()) {
                        emptyState.visibility = View.VISIBLE
                        bottomBar.visibility = View.GONE
                        viewPager.visibility = View.GONE
                    } else {
                        emptyState.visibility = View.GONE
                        bottomBar.visibility = View.VISIBLE
                        viewPager.visibility = View.VISIBLE

                        // Update displays for current position
                        val currentPos = viewPager.currentItem.coerceIn(0, pageList.size - 1)
                        updatePageIndicator(currentPos)
                        updateConfidenceDisplay(currentPos)
                        updateRecaptureButton(currentPos)
                    }
                }
            }
        }

        // Restore position if coming back from recapture
        val startPosition = arguments?.getInt(ARG_START_POSITION, 0) ?: 0
        if (startPosition > 0) {
            viewPager.post {
                if (startPosition < adapter.itemCount) {
                    viewPager.setCurrentItem(startPosition, false)
                }
            }
        }
    }

    private fun updatePageIndicator(position: Int) {
        val total = pages.size
        tvPageIndicator.text = "${position + 1} of $total"
    }

    private fun updateConfidenceDisplay(position: Int) {
        val page = pages.getOrNull(position) ?: return
        val confidencePercent = (page.confidence * 100).toInt()

        when (page.processingStatus) {
            "PENDING", "PROCESSING" -> {
                tvConfidence.text = "Processing..."
                tvConfidence.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            "FAILED" -> {
                tvConfidence.text = "Failed"
                tvConfidence.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            }
            else -> {
                tvConfidence.text = "$confidencePercent%"
                val color = when {
                    page.confidence >= 0.8f -> R.color.success_green
                    page.confidence >= 0.6f -> R.color.warning
                    else -> R.color.error_red
                }
                tvConfidence.setTextColor(ContextCompat.getColor(requireContext(), color))
            }
        }
    }

    private fun updateRecaptureButton(position: Int) {
        val page = pages.getOrNull(position) ?: return

        when (page.processingStatus) {
            "PENDING", "PROCESSING" -> {
                btnRecapture.isEnabled = false
                btnRecapture.text = "Processing..."
            }
            "FAILED" -> {
                btnRecapture.isEnabled = true
                btnRecapture.text = "Retry Capture"
            }
            else -> {
                btnRecapture.isEnabled = true
                btnRecapture.text = "Recapture"
            }
        }
    }

    private fun navigateToCameraForRecapture(page: Page) {
        // Prepare the page for recapture
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.prepareForRecapture(page.id)

            // Navigate to camera in recapture mode
            val cameraFragment = CameraFragment.newInstanceForRecapture(
                bookId = page.bookId,
                pageIdToReplace = page.id,
                expectedPageNumber = page.detectedPageNumber ?: page.pageNumber,
                returnToPosition = viewPager.currentItem
            )

            parentFragmentManager.beginTransaction()
                .replace(R.id.container, cameraFragment)
                .addToBackStack(null)
                .commit()
        }
    }

    companion object {
        private const val ARG_BOOK_ID = "book_id"
        private const val ARG_START_POSITION = "start_position"

        fun newInstance(bookId: Long, startPosition: Int = 0) = PageReviewFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_BOOK_ID, bookId)
                putInt(ARG_START_POSITION, startPosition)
            }
        }
    }
}
