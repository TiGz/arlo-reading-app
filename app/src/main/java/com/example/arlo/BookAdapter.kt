package com.example.arlo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.arlo.data.Book
import com.example.arlo.databinding.ItemBookBinding
import java.io.File

data class BookWithInfo(
    val book: Book,
    val pageCount: Int = 0
)

class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (BookWithInfo) -> Unit = {}
) : ListAdapter<BookWithInfo, BookAdapter.BookViewHolder>(BookDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookViewHolder(private val binding: ItemBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(bookWithInfo: BookWithInfo) {
            val book = bookWithInfo.book
            val pageCount = bookWithInfo.pageCount

            binding.tvTitle.text = book.title

            // Page count display
            binding.tvPageCount.text = when (pageCount) {
                0 -> "No pages"
                1 -> "1 page"
                else -> "$pageCount pages"
            }

            // Last read position
            if (book.lastReadPageNumber > 1 && pageCount > 1) {
                binding.tvLastRead.text = "Page ${book.lastReadPageNumber}"
                binding.tvLastRead.visibility = View.VISIBLE
            } else {
                binding.tvLastRead.visibility = View.GONE
            }

            // Load cover image
            val coverPath = book.coverImagePath
            if (!coverPath.isNullOrEmpty()) {
                val file = File(coverPath)
                if (file.exists()) {
                    binding.ivBookCover.load(file) {
                        crossfade(true)
                        placeholder(R.drawable.bg_book_cover_placeholder)
                        error(R.drawable.bg_book_cover_placeholder)
                    }
                } else {
                    binding.ivBookCover.setImageResource(0)
                    binding.ivBookCover.setBackgroundResource(R.drawable.bg_book_cover_placeholder)
                }
            } else {
                binding.ivBookCover.setImageResource(0)
                binding.ivBookCover.setBackgroundResource(R.drawable.bg_book_cover_placeholder)
            }

            // Progress indicator (show if reading in progress)
            if (book.lastReadPageNumber > 1 && pageCount > 1) {
                binding.progressOverlay.visibility = View.VISIBLE
                val progress = (book.lastReadPageNumber.toFloat() / pageCount.toFloat())
                val params = binding.progressOverlay.layoutParams
                // Set width as percentage - this needs ConstraintLayout percentage
                binding.progressOverlay.post {
                    val parentWidth = (binding.progressOverlay.parent as View).width
                    params.width = (parentWidth * progress).toInt()
                    binding.progressOverlay.layoutParams = params
                }
            } else {
                binding.progressOverlay.visibility = View.GONE
            }

            binding.cardBook.setOnClickListener { onBookClick(book) }
            binding.cardBook.setOnLongClickListener {
                onBookLongClick(bookWithInfo)
                true
            }
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<BookWithInfo>() {
        override fun areItemsTheSame(oldItem: BookWithInfo, newItem: BookWithInfo): Boolean {
            return oldItem.book.id == newItem.book.id
        }

        override fun areContentsTheSame(oldItem: BookWithInfo, newItem: BookWithInfo): Boolean {
            return oldItem == newItem
        }
    }
}
