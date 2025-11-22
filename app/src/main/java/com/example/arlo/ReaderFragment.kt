package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.arlo.databinding.FragmentReaderBinding

class ReaderFragment : Fragment() {

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReaderViewModel by viewModels()
    private var bookId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            bookId = it.getLong(ARG_BOOK_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = PageAdapter(this, emptyList()) {
            // Launch Camera in ADD_PAGES mode
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_ADD_PAGES, bookId))
                .addToBackStack(null)
                .commit()
        }
        binding.viewPager.adapter = adapter

        if (bookId != -1L) {
            viewModel.getPages(bookId).observe(viewLifecycleOwner) { pages ->
                val previousSize = adapter.itemCount
                adapter.updatePages(pages)
                // If pages added, scroll to the new page (optional, but good UX)
                // Note: itemCount includes the AddPageFragment
                if (adapter.itemCount > previousSize && previousSize > 1) {
                     // binding.viewPager.setCurrentItem(pages.size - 1, true) 
                     // Maybe don't auto-scroll, let user decide.
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_BOOK_ID = "book_id"

        @JvmStatic
        fun newInstance(bookId: Long) =
            ReaderFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BOOK_ID, bookId)
                }
            }
    }
}
