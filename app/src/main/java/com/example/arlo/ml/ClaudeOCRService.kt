package com.example.arlo.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.arlo.data.SentenceData
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for extracting text from images using Claude Haiku API.
 */
class ClaudeOCRService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Single page result from OCR
    data class PageOCRResult(
        val pageNumber: Int?,
        val confidence: Float,
        val sentences: List<SentenceData>,
        val fullText: String
    )

    // Full OCR result (may contain multiple pages)
    data class OCRResult(
        val pages: List<PageOCRResult>
    ) {
        // Convenience for single-page backwards compatibility
        val firstPage: PageOCRResult? get() = pages.firstOrNull()
        val sentences: List<SentenceData> get() = firstPage?.sentences ?: emptyList()
        val fullText: String get() = firstPage?.fullText ?: ""
        val detectedPageNumber: Int? get() = firstPage?.pageNumber
        val confidence: Float get() = firstPage?.confidence ?: 1.0f
    }

    /**
     * Extract sentences from an image using Claude Haiku.
     * @param imageUri URI to the image file
     * @param apiKey Anthropic API key
     * @return OCRResult with sentences and full text
     */
    suspend fun extractSentences(imageUri: Uri, apiKey: String): OCRResult = withContext(Dispatchers.IO) {
        val base64Image = prepareImage(imageUri)
        extractWithRetry(base64Image, apiKey)
    }

    /**
     * Extract title from a book cover image.
     * Returns the first meaningful text found, cleaned up.
     */
    suspend fun extractTitle(imageUri: Uri, apiKey: String): String = withContext(Dispatchers.IO) {
        val base64Image = prepareImage(imageUri)
        extractTitleFromImage(base64Image, apiKey)
    }

    private suspend fun prepareImage(imageUri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(imageUri)
            ?: throw IOException("Cannot read image")

        val bitmap = inputStream.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("Cannot decode image")

        // Resize to max 1500px on longest side for API limits
        val maxDimension = 1500
        val scale = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height,
            1f
        )

        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }

        // Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)

        // Clean up if we created a new bitmap
        if (resized != bitmap) {
            resized.recycle()
        }

        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun extractWithRetry(
        base64Image: String,
        apiKey: String,
        maxRetries: Int = 3
    ): OCRResult {
        var lastError: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return callClaudeAPI(base64Image, apiKey, OCR_PROMPT)
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    delay(1000L * (attempt + 1)) // 1s, 2s, 3s backoff
                }
            } catch (e: ApiException) {
                when (e.code) {
                    401 -> throw InvalidApiKeyException("API key is invalid or expired")
                    429 -> {
                        lastError = e
                        delay(5000L) // Rate limited, wait longer
                    }
                    else -> throw e
                }
            }
        }

        throw lastError ?: IOException("OCR failed after $maxRetries attempts")
    }

    private suspend fun extractTitleFromImage(base64Image: String, apiKey: String): String {
        return try {
            Log.d(TAG, "Extracting title from cover image...")
            val result = callClaudeAPI(base64Image, apiKey, TITLE_PROMPT)
            // Return first sentence or full text, cleaned up
            val title = result.sentences.firstOrNull()?.text ?: result.fullText
            Log.d(TAG, "Extracted title: $title")
            title.take(50).trim()
        } catch (e: Exception) {
            // Fallback to timestamp-based title
            Log.e(TAG, "Title extraction failed, using fallback", e)
            "Book ${System.currentTimeMillis()}"
        }
    }

    private fun callClaudeAPI(base64Image: String, apiKey: String, prompt: String): OCRResult {
        val requestBody = buildRequestJson(base64Image, prompt)

        Log.d(TAG, "API Key being used: '${apiKey.take(30)}...' (length=${apiKey.length})")

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("x-api-key", apiKey)
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "no body"
            Log.e(TAG, "API error ${response.code}: $errorBody")
            throw ApiException(response.code, "API error: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response from API")

        return parseResponse(responseBody)
    }

    private fun buildRequestJson(base64Image: String, prompt: String): String {
        val messageContent = listOf(
            mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to "image/jpeg",
                    "data" to base64Image
                )
            ),
            mapOf(
                "type" to "text",
                "text" to prompt
            )
        )

        val requestMap = mapOf(
            "model" to "claude-3-5-haiku-20241022",
            "max_tokens" to 4096,
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to messageContent
                )
            )
        )

        return gson.toJson(requestMap)
    }

    private fun parseResponse(responseBody: String): OCRResult {
        try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("content")

            if (content.size() == 0) {
                return OCRResult(emptyList())
            }

            val textContent = content[0].asJsonObject.get("text").asString

            // Try to parse as JSON first
            return try {
                parseJsonResponse(textContent)
            } catch (e: Exception) {
                // Fallback: treat as plain text and split into sentences
                parseTextAsSentences(textContent)
            }
        } catch (e: Exception) {
            throw IOException("Failed to parse API response: ${e.message}")
        }
    }

    private fun parseJsonResponse(text: String): OCRResult {
        // Try new multi-page format first
        val pagesPattern = """\{[\s\S]*"pages"[\s\S]*\}""".toRegex()
        val pagesMatch = pagesPattern.find(text)

        if (pagesMatch != null) {
            return parseMultiPageResponse(pagesMatch.value)
        }

        // Fallback to old single-page format for backwards compatibility
        val oldPattern = """\{[\s\S]*"sentences"[\s\S]*\}""".toRegex()
        val oldMatch = oldPattern.find(text)
            ?: throw IllegalArgumentException("No JSON found in response")

        return parseOldFormatResponse(oldMatch.value)
    }

    private fun parseMultiPageResponse(jsonStr: String): OCRResult {
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val pagesArray = json.getAsJsonArray("pages")

        val pages = pagesArray.map { pageElement ->
            val pageObj = pageElement.asJsonObject
            val pageNumber = pageObj.get("pageNumber")?.let {
                if (!it.isJsonNull) it.asInt else null
            }
            val confidence = pageObj.get("confidence")?.asFloat ?: 1.0f
            val sentencesArray = pageObj.getAsJsonArray("sentences")

            val sentences = sentencesArray.map { element ->
                val obj = element.asJsonObject
                SentenceData(
                    text = obj.get("text").asString,
                    isComplete = obj.get("isComplete")?.asBoolean ?: true
                )
            }

            PageOCRResult(
                pageNumber = pageNumber,
                confidence = confidence,
                sentences = sentences,
                fullText = sentences.joinToString(" ") { it.text }
            )
        }

        return OCRResult(pages)
    }

    private fun parseOldFormatResponse(jsonStr: String): OCRResult {
        val json = JsonParser.parseString(jsonStr).asJsonObject

        // Extract page number if present
        val pageNumber = json.get("pageNumber")?.let { element ->
            if (!element.isJsonNull) element.asInt else null
        }

        val sentencesArray = json.getAsJsonArray("sentences")
        val sentences = sentencesArray.map { element ->
            val obj = element.asJsonObject
            SentenceData(
                text = obj.get("text").asString,
                isComplete = obj.get("isComplete")?.asBoolean ?: true
            )
        }

        val fullText = sentences.joinToString(" ") { it.text }

        // Wrap in new format for consistency
        val page = PageOCRResult(
            pageNumber = pageNumber,
            confidence = 1.0f, // Old format doesn't have confidence
            sentences = sentences,
            fullText = fullText
        )
        return OCRResult(listOf(page))
    }

    private fun parseTextAsSentences(text: String): OCRResult {
        // Simple sentence splitting for fallback
        val sentences = text
            .replace("\n", " ")
            .split(Regex("""(?<=[.!?])\s+"""))
            .filter { it.isNotBlank() }
            .map { SentenceData(text = it.trim(), isComplete = true) }
            .toMutableList()

        // Mark last sentence as potentially incomplete if it doesn't end with punctuation
        if (sentences.isNotEmpty()) {
            val last = sentences.last()
            if (!last.text.endsWith(".") && !last.text.endsWith("!") && !last.text.endsWith("?")) {
                sentences[sentences.lastIndex] = last.copy(isComplete = false)
            }
        }

        // Wrap in new format for consistency
        val page = PageOCRResult(
            pageNumber = null,
            confidence = 0.5f, // Fallback parsing = lower confidence
            sentences = sentences,
            fullText = text
        )
        return OCRResult(listOf(page))
    }

    class ApiException(val code: Int, message: String) : Exception(message)
    class InvalidApiKeyException(message: String) : Exception(message)

    companion object {
        private const val TAG = "ClaudeOCR"
        private const val API_URL = "https://api.anthropic.com/v1/messages"

        private val OCR_PROMPT = """
Extract text from this book page image. This is a CHILDREN'S BOOK - expect varied text sizes, fonts, and layouts.

Return a JSON object with this exact format:
{
  "pages": [
    {
      "pageNumber": 42,
      "confidence": 0.92,
      "sentences": [
        {"text": "First sentence.", "isComplete": true},
        {"text": "Last sentence that continues", "isComplete": false}
      ]
    }
  ]
}

CRITICAL RULES:

PAGE DETECTION:
- If you see 2 or more FULL pages clearly visible, include ALL of them in the array
- If you see 1 full page + 1 partial page (cut off), include ONLY the full page
- A partial page is one where text is visibly cut off at edges or significantly obscured
- Order pages by their page number (left page before right page in a spread)

CHILDREN'S BOOK TEXT:
- Capture ALL text including large display text, speech bubbles, captions
- Text may be in various sizes, fonts, colors, and orientations
- EXCLUDE chapter titles at the top of pages (e.g., "Chapter 3", "Chapter Three")
- EXCLUDE book titles/headers that repeat on every page
- EXCLUDE page footers with book title or author name
- INCLUDE the story content, dialogue, and narrative text

CONFIDENCE SCORE (0.0 to 1.0):
- 0.9-1.0: Crystal clear, sharp text, no blur, complete page visible
- 0.7-0.9: Good quality, minor issues (slight blur, small shadows)
- 0.5-0.7: Readable but has issues (moderate blur, glare, finger shadows)
- Below 0.5: Poor quality, significant text unclear

PAGE NUMBER:
- Extract the printed page number if visible (top, bottom, corners)
- Use null if no page number is visible
- Do NOT use chapter numbers as page numbers

SENTENCE EXTRACTION:
- Split into sentences ending with . ! or ?
- IGNORE periods in abbreviations (Dr., Mr., Mrs., U.S., etc., e.g., i.e.)
- IGNORE periods in numbers (${'$'}4.99, 3.14)
- Keep dialogue punctuation with the sentence
- Mark isComplete: false ONLY for the last sentence if it ends mid-thought

TEXT VALIDATION - IMPORTANT:
- After extracting text, CHECK that words make sense in context
- Common OCR misreads to fix: "rn" misread as "m", "cl" as "d", "vv" as "w"
- If a word looks wrong, use context to determine the correct word
- Example: "unween" should be "unseen", "rnother" should be "mother"
- Prioritize real English words that fit the sentence meaning

Return ONLY the JSON object, no other text.
        """.trimIndent()

        private val TITLE_PROMPT = """
Extract the book title from this cover image. Return a JSON object:
{
  "sentences": [
    {"text": "The Book Title", "isComplete": true}
  ]
}

Return ONLY the main title, not subtitle or author name. Return ONLY the JSON object.
        """.trimIndent()
    }
}
