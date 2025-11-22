package com.example.arlo

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.arlo.data.Page

class PageAdapter(fragment: Fragment, private var pages: List<Page>, private val onAddPageClick: () -> Unit) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = pages.size + 1

    override fun createFragment(position: Int): Fragment {
        if (position == pages.size) {
            val fragment = AddPageFragment()
            fragment.setOnAddPageClickListener(onAddPageClick)
            return fragment
        }
        return PageFragment.newInstance(pages[position].text)
    }
    
    fun updatePages(newPages: List<Page>) {
        pages = newPages
        notifyDataSetChanged()
    }
}
