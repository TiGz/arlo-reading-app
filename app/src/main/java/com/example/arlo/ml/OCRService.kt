package com.example.arlo.ml

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.IOException

class OCRService(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun processImage(imageUri: Uri): String {
        return try {
            val image = InputImage.fromFilePath(context, imageUri)
            val result = recognizer.process(image).await()
            result.text
        } catch (e: IOException) {
            e.printStackTrace()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
