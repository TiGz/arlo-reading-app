package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.arlo.databinding.FragmentPageBinding

class PageFragment : Fragment() {

    private var _binding: FragmentPageBinding? = null
    private val binding get() = _binding!!

    private var pageText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pageText = it.getString(ARG_TEXT)
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
        binding.tvContent.text = pageText
        
        val ttsService = (requireActivity().application as ArloApplication).ttsService

        binding.tvContent.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val layout = binding.tvContent.layout
                val x = event.x.toInt()
                val y = event.y.toInt()

                if (layout != null) {
                    val line = layout.getLineForVertical(y)
                    val offset = layout.getOffsetForHorizontal(line, x.toFloat())

                    // Find word boundaries
                    val text = pageText ?: ""
                    if (offset in text.indices) {
                        // Simple word boundary search
                        var start = offset
                        var end = offset
                        while (start > 0 && !text[start - 1].isWhitespace()) start--
                        while (end < text.length && !text[end].isWhitespace()) end++

                        // Speak from start
                        val textToSpeak = text.substring(start)
                        ttsService.setOnRangeStartListener { startOffset, endOffset ->
                            activity?.runOnUiThread {
                                highlightWord(start + startOffset, start + endOffset)
                            }
                        }
                        ttsService.speak(textToSpeak)
                    }
                }
            }
            true
        }
    }

    private fun highlightWord(start: Int, end: Int) {
        val text = pageText ?: return
        val spannable = android.text.SpannableString(text)
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(android.graphics.Color.YELLOW),
            start,
            end,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvContent.text = spannable
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TEXT = "text"

        @JvmStatic
        fun newInstance(text: String) =
            PageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT, text)
                }
            }
    }
}
