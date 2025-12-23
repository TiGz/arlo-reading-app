package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.example.arlo.databinding.FragmentLibraryBinding

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = BookAdapter(
            onBookClick = { book ->
                // Navigate to Reader
                (activity as? MainActivity)?.clearNavSelection()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, UnifiedReaderFragment.newInstance(book.id))
                    .addToBackStack(null)
                    .commit()
            },
            onBookLongClick = { bookWithInfo ->
                showDeleteConfirmation(bookWithInfo)
            }
        )

        // Use GridLayoutManager with 2 columns for book cover display
        binding.recyclerView.layoutManager = GridLayoutManager(context, 2)
        binding.recyclerView.adapter = adapter

        // Observe books with page count info
        viewModel.allBooksWithInfo.observe(viewLifecycleOwner) { booksWithInfo ->
            adapter.submitList(booksWithInfo)
            binding.emptyState.visibility = if (booksWithInfo.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (booksWithInfo.isEmpty()) View.GONE else View.VISIBLE

            // Update subtitle with fun, encouraging message
            binding.tvSubtitle.text = when (booksWithInfo.size) {
                0 -> "Your reading adventures await!"
                1 -> "1 awesome book ready to read!"
                else -> "${booksWithInfo.size} books in your adventure!"
            }
        }

        binding.fabAddBook.setOnClickListener {
            // Navigate to Camera for new book (cover capture flow)
            (activity as? MainActivity)?.clearNavSelection()
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_NEW_BOOK))
                .addToBackStack(null)
                .commit()
        }
    }

    private fun showDeleteConfirmation(bookWithInfo: BookWithInfo) {
        val book = bookWithInfo.book
        val pageCount = bookWithInfo.pageCount
        val pageText = if (pageCount == 1) "1 page" else "$pageCount pages"

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Book")
            .setMessage("Delete \"${book.title}\" and all its $pageText?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteBook(book.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
