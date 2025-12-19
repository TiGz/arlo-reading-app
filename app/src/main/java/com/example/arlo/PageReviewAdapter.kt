package com.example.arlo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.arlo.data.Page

class PageReviewAdapter : ListAdapter<Page, PageReviewAdapter.PageViewHolder>(PageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page_review, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun getPageAt(position: Int): Page? {
        return if (position in 0 until itemCount) getItem(position) else null
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPageNumber: TextView = itemView.findViewById(R.id.tvPageNumber)
        private val ivConfidenceIcon: ImageView = itemView.findViewById(R.id.ivConfidenceIcon)
        private val tvConfidenceScore: TextView = itemView.findViewById(R.id.tvConfidenceScore)
        private val tvFullText: TextView = itemView.findViewById(R.id.tvFullText)
        private val processingOverlay: View = itemView.findViewById(R.id.processingOverlay)
        private val errorIndicator: View = itemView.findViewById(R.id.errorIndicator)
        private val tvErrorMessage: TextView = itemView.findViewById(R.id.tvErrorMessage)

        fun bind(page: Page) {
            // Page number display - show detected label, chapter title, or sequence number
            val pageDisplay = buildString {
                page.chapterTitle?.let { append("$it • ") }
                append("Page ")
                append(page.detectedPageLabel ?: page.pageNumber.toString())
            }
            tvPageNumber.text = pageDisplay

            // Confidence display with color coding
            val confidencePercent = (page.confidence * 100).toInt()
            tvConfidenceScore.text = "$confidencePercent% quality"

            val context = itemView.context
            when {
                page.confidence >= 0.8f -> {
                    ivConfidenceIcon.setImageResource(R.drawable.ic_check)
                    ivConfidenceIcon.setColorFilter(ContextCompat.getColor(context, R.color.success_green))
                    tvConfidenceScore.setTextColor(ContextCompat.getColor(context, R.color.success_green))
                }
                page.confidence >= 0.6f -> {
                    ivConfidenceIcon.setImageResource(R.drawable.ic_warning)
                    ivConfidenceIcon.setColorFilter(ContextCompat.getColor(context, R.color.warning))
                    tvConfidenceScore.setTextColor(ContextCompat.getColor(context, R.color.warning))
                }
                else -> {
                    ivConfidenceIcon.setImageResource(R.drawable.ic_warning)
                    ivConfidenceIcon.setColorFilter(ContextCompat.getColor(context, R.color.error_red))
                    tvConfidenceScore.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                }
            }

            // Handle different processing states
            when (page.processingStatus) {
                "PENDING", "PROCESSING" -> {
                    processingOverlay.visibility = View.VISIBLE
                    errorIndicator.visibility = View.GONE
                    tvFullText.text = page.text.ifEmpty { "Processing..." }
                    tvFullText.alpha = 0.5f
                    ivConfidenceIcon.visibility = View.GONE
                    tvConfidenceScore.visibility = View.GONE
                }
                "FAILED" -> {
                    processingOverlay.visibility = View.GONE
                    errorIndicator.visibility = View.VISIBLE
                    tvErrorMessage.text = page.errorMessage ?: "Processing failed"
                    tvFullText.text = ""
                    tvFullText.alpha = 1.0f
                    ivConfidenceIcon.visibility = View.GONE
                    tvConfidenceScore.visibility = View.GONE
                }
                "COMPLETED" -> {
                    processingOverlay.visibility = View.GONE
                    errorIndicator.visibility = View.GONE
                    tvFullText.text = page.text
                    tvFullText.alpha = 1.0f
                    ivConfidenceIcon.visibility = View.VISIBLE
                    tvConfidenceScore.visibility = View.VISIBLE
                }
                else -> {
                    processingOverlay.visibility = View.GONE
                    errorIndicator.visibility = View.GONE
                    tvFullText.text = page.text
                    tvFullText.alpha = 1.0f
                }
            }
        }
    }

    class PageDiffCallback : DiffUtil.ItemCallback<Page>() {
        override fun areItemsTheSame(oldItem: Page, newItem: Page): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Page, newItem: Page): Boolean {
            return oldItem == newItem
        }
    }
}
