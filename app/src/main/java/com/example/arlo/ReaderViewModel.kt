package com.example.arlo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.example.arlo.data.Page

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ArloApplication).repository

    fun getPages(bookId: Long): LiveData<List<Page>> {
        return repository.getPages(bookId).asLiveData()
    }
}
