package com.example.arlo.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room TypeConverter for storing List<SentenceData> as JSON string.
 */
class SentenceListConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromSentenceList(sentences: List<SentenceData>?): String? {
        return sentences?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toSentenceList(json: String?): List<SentenceData>? {
        if (json.isNullOrBlank()) return null
        val type = object : TypeToken<List<SentenceData>>() {}.type
        return gson.fromJson(json, type)
    }
}
