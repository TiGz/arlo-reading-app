package com.example.arlo.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TTSService(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onRangeStart: ((Int, Int) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The Language specified is not supported!")
            } else {
                isInitialized = true
                setupUtteranceProgressListener()
            }
        } else {
            Log.e(TAG, "Initialization Failed!")
        }
    }

    private fun setupUtteranceProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {}

            override fun onError(utteranceId: String?) {}

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                onRangeStart?.invoke(start, end)
            }
        })
    }

    fun setOnRangeStartListener(listener: (Int, Int) -> Unit) {
        onRangeStart = listener
    }

    fun speak(text: String) {
        if (isInitialized) {
            val params = Bundle()
            // params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utteranceId") 
            // Note: onRangeStart requires an utterance ID to be set in some versions, 
            // but in modern Android it's passed via the speak method's bundle or id.
            // Actually, for onRangeStart to work, we might need to ensure we are using the right speak method overload.
            
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
        } else {
            Log.e(TAG, "TTS not initialized")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "TTSService"
    }
}
