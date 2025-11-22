package com.example.arlo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.arlo.databinding.ItemAddPageBinding

class AddPageFragment : Fragment() {

    private var _binding: ItemAddPageBinding? = null
    private val binding get() = _binding!!
    private var onAddPageClick: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ItemAddPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnAddPage.setOnClickListener {
            onAddPageClick?.invoke()
        }
    }

    fun setOnAddPageClickListener(listener: () -> Unit) {
        onAddPageClick = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
