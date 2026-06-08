package com.odiss.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 대화 한 줄. role 은 "user" 또는 "odiss". */
data class ConversationEntry(val role: String, val text: String)

/**
 * 백그라운드 [HandsFreeService] 와 앱 UI([AssistantViewModel]) 가 공유하는 대화 스토어.
 *
 * 핸즈프리 서비스가 인식한 사용자 발화와 서버 응답을 여기에 적재하면,
 * 앱을 열었을 때 chat 모드 화면이 동일한 대화를 그대로 보여준다.
 */
object ConversationStore {
    private const val MAX_LINES = 50

    private val _messages = MutableStateFlow<List<ConversationEntry>>(emptyList())
    val messages: StateFlow<List<ConversationEntry>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun post(role: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val last = _messages.value.lastOrNull()
        if (last != null && last.role == role && last.text == trimmed) return
        _messages.value = (_messages.value + ConversationEntry(role, trimmed)).takeLast(MAX_LINES)
    }

    fun setStatus(status: String) {
        if (status.isBlank()) return
        _status.value = status
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
