package com.example.arlo

import android.app.Application
import com.example.arlo.data.AppDatabase
import com.example.arlo.data.BookRepository

class ArloApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { BookRepository(database.bookDao()) }
    val ttsService by lazy { com.example.arlo.tts.TTSService(this) }
}
