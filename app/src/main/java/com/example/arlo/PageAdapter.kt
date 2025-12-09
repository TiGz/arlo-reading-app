package com.example.arlo

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.arlo.data.Page

class PageAdapter(
    fragment: Fragment,
    private var pages: List<Page>,
    private val onAddPageClick: () -> Unit
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment {
        return PageFragment.newInstance(
            text = pages[position].text,
            pageId = pages[position].id
        )
    }

    override fun getItemId(position: Int): Long {
        return if (position < pages.size) pages[position].id else -1L
    }

    override fun containsItem(itemId: Long): Boolean {
        return pages.any { it.id == itemId }
    }

    fun updatePages(newPages: List<Page>) {
        pages = newPages
        notifyDataSetChanged()
    }

    fun getPageAt(position: Int): Page? {
        return if (position in pages.indices) pages[position] else null
    }
}
