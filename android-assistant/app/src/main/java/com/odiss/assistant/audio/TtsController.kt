package com.odiss.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class TtsController(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) {
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
