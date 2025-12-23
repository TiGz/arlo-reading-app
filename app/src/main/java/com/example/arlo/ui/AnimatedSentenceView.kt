package com.example.arlo.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.example.arlo.R
import com.example.arlo.data.TextStyle
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent

/**
 * A FlexboxLayout that displays a sentence as individual animated word views.
 * Each word can be independently animated for TTS playback, user turn indication,
 * and success/error feedback in collaborative reading mode.
 *
 * This replaces the single TextView approach with BackgroundColorSpan highlighting,
 * enabling professional-grade word-by-word animations.
 */
class AnimatedSentenceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FlexboxLayout(context, attrs, defStyleAttr) {

    // List of WordViews representing each word in the sentence
    private var wordViews: MutableList<WordView> = mutableListOf()

    // Current sentence text for reference
    private var currentSentence: String = ""

    // Track which word is currently highlighted
    private var highlightedWordIndex: Int = -1

    // Text styling
    private var textSizeSp: Float = 30f
    private var textColor: Int = Color.BLACK
    private var wordSpacingDp: Float = 4f  // Balanced spacing between words

    init {
        // FlexboxLayout configuration for natural sentence flow
        flexWrap = FlexWrap.WRAP
        justifyContent = JustifyContent.CENTER

        // Load default text color from theme
        textColor = ContextCompat.getColor(context, R.color.reader_text_primary)
    }

    // Track current display style (for change detection)
    private var currentTextStyle: TextStyle = TextStyle.NORMAL

    /**
     * Set the sentence to display. Parses into words and creates WordViews.
     *
     * @param sentence The sentence text to display
     * @param animate Whether to animate the word creation (staggered fade-in)
     * @param isChapterTitle Whether this is a chapter title (styled as bold heading) - legacy parameter
     * @param textStyle The text style to apply (overrides isChapterTitle if not NORMAL)
     */
    fun setSentence(
        sentence: String,
        animate: Boolean = false,
        isChapterTitle: Boolean = false,
        textStyle: TextStyle = TextStyle.NORMAL
    ) {
        // Determine effective style - textStyle takes precedence, then isChapterTitle
        val effectiveStyle = when {
            textStyle != TextStyle.NORMAL -> textStyle
            isChapterTitle -> TextStyle.HEADING
            else -> TextStyle.NORMAL
        }

        // Skip if same sentence and style
        if (sentence == currentSentence && wordViews.isNotEmpty() && effectiveStyle == currentTextStyle) return

        currentSentence = sentence
        highlightedWordIndex = -1
        currentTextStyle = effectiveStyle

        // Clear existing views
        removeAllViews()
        wordViews.clear()

        if (sentence.isBlank()) return

        // Parse sentence into words (preserving punctuation attached to words)
        val words = sentence.split(" ").filter { it.isNotEmpty() }

        // Create WordView for each word
        words.forEachIndexed { index, word ->
            val wordView = createWordView(word, effectiveStyle)

            if (animate) {
                // Staggered fade-in animation
                wordView.alpha = 0f
                wordView.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setStartDelay(index * 30L)
                    .start()
            }

            wordViews.add(wordView)
            addView(wordView)
        }
    }

    /**
     * Create a styled WordView for a single word.
     *
     * @param word The word text to display
     * @param style The text style to apply (affects size, weight, color)
     */
    private fun createWordView(word: String, style: TextStyle = TextStyle.NORMAL): WordView {
        return WordView(context).apply {
            text = word

            // Style-specific sizing and typography
            val (effectiveTextSize, typeface, effectiveColor) = when (style) {
                TextStyle.HEADING -> Triple(
                    textSizeSp * 1.3f,
                    Typeface.BOLD,
                    textColor
                )
                TextStyle.SCENE -> Triple(
                    textSizeSp * 1.1f,
                    Typeface.BOLD_ITALIC,
                    ContextCompat.getColor(context, R.color.scene_text)  // Slightly muted color
                )
                TextStyle.NORMAL -> Triple(
                    textSizeSp,
                    Typeface.NORMAL,
                    textColor
                )
            }

            setTextSize(TypedValue.COMPLEX_UNIT_SP, effectiveTextSize)
            setTextColor(effectiveColor)
            setTypeface(Typeface.SERIF, typeface)
            gravity = Gravity.CENTER

            // Layout params - horizontal spacing only to maintain sentence flow
            val hSpacingPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                wordSpacingDp,
                resources.displayMetrics
            ).toInt()

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                // Only horizontal margins to keep words on same baseline
                setMargins(hSpacingPx, 0, hSpacingPx, 0)
            }

            // Small padding for background highlight (rounded corners effect)
            val paddingPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                2f,
                resources.displayMetrics
            ).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
    }

    /**
     * Highlight a specific word with the given state.
     *
     * @param wordIndex Index of the word to highlight (0-based)
     * @param state The highlight state to apply
     */
    fun highlightWord(wordIndex: Int, state: WordHighlightState) {
        Log.d("AnimatedSentence", "highlightWord: index=$wordIndex, state=$state, wordCount=${wordViews.size}")

        // Clear previous highlight if different word
        if (highlightedWordIndex >= 0 && highlightedWordIndex != wordIndex) {
            wordViews.getOrNull(highlightedWordIndex)?.transitionTo(WordHighlightState.IDLE)
        }

        highlightedWordIndex = wordIndex

        // Apply new highlight
        val wordView = wordViews.getOrNull(wordIndex)
        if (wordView != null) {
            Log.d("AnimatedSentence", "Highlighting word: '${wordView.text}'")
            wordView.transitionTo(state)
        } else {
            Log.w("AnimatedSentence", "Word index $wordIndex out of bounds")
        }
    }

    /**
     * Highlight a range of words (for multi-word targets like "read it").
     */
    fun highlightWordRange(startIndex: Int, endIndex: Int, state: WordHighlightState) {
        Log.d("AnimatedSentence", "highlightWordRange: $startIndex to $endIndex, state=$state")

        // Clear all previous highlights
        resetAllHighlights()

        // Highlight the range
        for (i in startIndex..minOf(endIndex, wordViews.lastIndex)) {
            val wordView = wordViews.getOrNull(i)
            Log.d("AnimatedSentence", "Highlighting range word[$i]: '${wordView?.text}'")
            wordView?.transitionTo(state)
        }

        highlightedWordIndex = startIndex
    }

    /**
     * Convert character range to word index.
     * Used to map TTS callback character positions to word indices.
     *
     * @param charStart Start character position in the sentence
     * @param charEnd End character position in the sentence
     * @return The word index containing the start position, or -1 if not found
     */
    fun charRangeToWordIndex(charStart: Int, charEnd: Int): Int {
        if (currentSentence.isEmpty()) return -1

        val words = currentSentence.split(" ")
        var charPos = 0

        words.forEachIndexed { index, word ->
            val wordEnd = charPos + word.length
            if (charStart >= charPos && charStart < wordEnd) {
                return index
            }
            charPos = wordEnd + 1 // +1 for space
        }

        return -1
    }

    /**
     * Find word index by matching the word text.
     * Useful for finding target words in collaborative mode.
     *
     * @param targetWord The word to find (case-insensitive)
     * @param fromEnd If true, search from end of sentence (for "last word" scenarios)
     * @return The word index, or -1 if not found
     */
    fun findWordIndex(targetWord: String, fromEnd: Boolean = false): Int {
        val normalizedTarget = targetWord.lowercase().trim()

        // Remove punctuation for matching
        fun normalize(word: String) = word.lowercase().replace(Regex("[.!?,;:\"'()\\[\\]]"), "")

        if (fromEnd) {
            for (i in wordViews.lastIndex downTo 0) {
                if (normalize(wordViews[i].text.toString()) == normalizedTarget) {
                    return i
                }
            }
        } else {
            wordViews.forEachIndexed { index, wordView ->
                if (normalize(wordView.text.toString()) == normalizedTarget) {
                    return index
                }
            }
        }

        return -1
    }

    /**
     * Get indices for the last N words in the sentence.
     * Used for collaborative mode where user reads multiple words.
     *
     * @param count Number of words from the end
     * @return Pair of (startIndex, endIndex) inclusive
     */
    fun getLastNWordsRange(count: Int): Pair<Int, Int>? {
        if (wordViews.isEmpty()) return null

        val startIndex = maxOf(0, wordViews.size - count)
        val endIndex = wordViews.lastIndex

        return startIndex to endIndex
    }

    /**
     * Reset all words to idle state.
     */
    fun resetAllHighlights() {
        highlightedWordIndex = -1
        wordViews.forEach { it.transitionTo(WordHighlightState.IDLE) }
    }

    /**
     * Immediately reset all words without animation (for sentence changes).
     */
    fun resetImmediate() {
        highlightedWordIndex = -1
        wordViews.forEach { it.resetImmediate() }
    }

    /**
     * Get the currently highlighted word index.
     */
    fun getHighlightedWordIndex(): Int = highlightedWordIndex

    /**
     * Get the number of words in the current sentence.
     */
    fun getWordCount(): Int = wordViews.size

    /**
     * Set text size for all words.
     */
    fun setTextSizeSp(sizeSp: Float) {
        textSizeSp = sizeSp
        wordViews.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp) }
    }

    /**
     * Set text color for all words.
     */
    fun setWordTextColor(color: Int) {
        textColor = color
        wordViews.forEach { it.setTextColor(color) }
    }

    /**
     * Apply incomplete sentence styling (orange tint).
     */
    fun setIncomplete(incomplete: Boolean) {
        val color = if (incomplete) {
            ContextCompat.getColor(context, R.color.warning)
        } else {
            ContextCompat.getColor(context, R.color.reader_text_primary)
        }
        wordViews.forEach { it.setTextColor(color) }
    }
}
