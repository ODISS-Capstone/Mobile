package com.odiss.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TtsController(context: Context) {
    private var tts: TextToSpeech? = null

    /** utteranceId -> 완료 콜백. 발화 종료 시 호출 후 제거된다. */
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { doneCallbacks.remove(it)?.invoke() }
            }
        })
    }

    /**
     * @param onDone 발화가 끝나면(또는 실패하면) 호출되는 콜백. 핸즈프리 연속 대화 재개에 사용한다.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            doneCallbacks[id] = onDone
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            doneCallbacks.remove(id)?.invoke()
        }
    }

    fun shutdown() {
        doneCallbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
