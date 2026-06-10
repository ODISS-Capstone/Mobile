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

    /** 재생 중/큐 대기 중인 utteranceId 집합. 마이크 게이팅(에코 방지)에 사용한다. */
    private val activeUtterances = ConcurrentHashMap.newKeySet<String>()

    /** TTS가 말하는 중(또는 큐에 대기 중)인지 여부. */
    val isSpeaking: Boolean
        get() = activeUtterances.isNotEmpty()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let {
                    activeUtterances.remove(it)
                    doneCallbacks.remove(it)?.invoke()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // stop()/flush로 끊긴 발화도 완료로 처리해 listening 재개가 막히지 않게 한다.
                utteranceId?.let {
                    activeUtterances.remove(it)
                    doneCallbacks.remove(it)?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let {
                    activeUtterances.remove(it)
                    doneCallbacks.remove(it)?.invoke()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let {
                    activeUtterances.remove(it)
                    doneCallbacks.remove(it)?.invoke()
                }
            }
        })
    }

    /**
     * 발화를 큐에 추가한다(QUEUE_ADD). filler 직후 본 응답이 도착해도 filler가
     * 중간에 끊기지 않고 순서대로 재생된다.
     *
     * @param flush true면 재생 중/대기 중 발화를 모두 끊고 즉시 말한다.
     * @param onDone 발화가 끝나면(또는 실패하면) 호출되는 콜백. 핸즈프리 연속 대화 재개에 사용한다.
     */
    fun speak(text: String, flush: Boolean = false, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            doneCallbacks[id] = onDone
        }
        activeUtterances.add(id)
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (flush) {
            // 플러시되는 기존 발화는 onStop이 정리하지만, 엔진이 안 불러줄 수도 있어 현재 id만 남긴다.
            activeUtterances.retainAll(setOf(id))
        }
        val result = tts?.speak(text, queueMode, null, id)
        if (result != TextToSpeech.SUCCESS) {
            activeUtterances.remove(id)
            doneCallbacks.remove(id)?.invoke()
        }
    }

    /** 재생 중/대기 중 발화를 모두 중단한다. 끊긴 발화의 onDone은 onStop 콜백으로 처리된다. */
    fun stop() {
        tts?.stop()
        // 일부 TTS 엔진은 stop() 시 onStop을 호출하지 않으므로 남은 콜백을 직접 비운다.
        activeUtterances.clear()
        val pending = doneCallbacks.values.toList()
        doneCallbacks.clear()
        pending.forEach { runCatching { it.invoke() } }
    }

    fun shutdown() {
        activeUtterances.clear()
        doneCallbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
