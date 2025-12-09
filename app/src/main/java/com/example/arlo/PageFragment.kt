package com.example.arlo

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.arlo.databinding.FragmentPageBinding

class PageFragment : Fragment() {

    private var _binding: FragmentPageBinding? = null
    private val binding get() = _binding!!

    private var pageText: String? = null
    private var pageId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pageText = it.getString(ARG_TEXT)
            pageId = it.getLong(ARG_PAGE_ID, -1L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Display the text
        binding.tvContent.text = pageText

        // Setup touch-to-read functionality
        setupTouchToRead()
    }

    private fun setupTouchToRead() {
        val ttsService = (requireActivity().application as ArloApplication).ttsService

        binding.tvContent.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val layout = binding.tvContent.layout ?: return@setOnTouchListener true
                val x = event.x.toInt() - binding.tvContent.paddingLeft
                val y = event.y.toInt() - binding.tvContent.paddingTop

                val line = layout.getLineForVertical(y)
                val offset = layout.getOffsetForHorizontal(line, x.toFloat())

                val text = pageText ?: return@setOnTouchListener true
                if (offset in text.indices) {
                    // Find word boundaries
                    var start = offset
                    var end = offset
                    while (start > 0 && !text[start - 1].isWhitespace()) start--
                    while (end < text.length && !text[end].isWhitespace()) end++

                    // Speak from tapped position
                    val textToSpeak = text.substring(start)

                    // Set up word highlighting
                    ttsService.setOnRangeStartListener { startOffset, endOffset ->
                        activity?.runOnUiThread {
                            highlightWord(start + startOffset, start + endOffset)
                        }
                    }

                    ttsService.speak(textToSpeak)
                }
            }
            true
        }
    }

    private fun highlightWord(start: Int, end: Int) {
        val text = pageText ?: return
        if (start < 0 || end > text.length || start >= end) return

        val spannable = SpannableString(text)
        val highlightColor = ContextCompat.getColor(requireContext(), R.color.highlight_word)
        spannable.setSpan(
            BackgroundColorSpan(highlightColor),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvContent.text = spannable
    }

    fun clearHighlight() {
        binding.tvContent.text = pageText
    }

    fun getPageId(): Long = pageId

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TEXT = "text"
        private const val ARG_PAGE_ID = "page_id"

        @JvmStatic
        fun newInstance(text: String, pageId: Long = -1L) =
            PageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT, text)
                    putLong(ARG_PAGE_ID, pageId)
                }
            }
    }
}
