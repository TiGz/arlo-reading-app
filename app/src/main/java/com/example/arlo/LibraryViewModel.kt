package com.example.arlo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.example.arlo.data.BookRepository

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private valrepository = (application as ArloApplication).repository
    val allBooks = repository.allBooks.asLiveData()
}
