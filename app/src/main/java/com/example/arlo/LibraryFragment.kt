package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
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

        val adapter = BookAdapter { book ->
            // Navigate to Reader
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, ReaderFragment.newInstance(book.id))
                .addToBackStack(null)
                .commit()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        viewModel.allBooks.observe(viewLifecycleOwner) { books ->
            adapter.submitList(books)
        }

        binding.fabAddBook.setOnClickListener {
            // Navigate to Camera
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, CameraFragment.newInstance(CameraFragment.MODE_NEW_BOOK))
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
