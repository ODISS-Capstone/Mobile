package com.odiss.assistant.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odiss.assistant.audio.TtsController
import com.odiss.assistant.data.OdissRepository
import com.odiss.assistant.model.MedicationInput
import com.odiss.assistant.model.WsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class ConnectionState { CHECKING, ONLINE, OFFLINE }

data class ChatLine(val role: String, val text: String)

data class AssistantUiState(
    val connection: ConnectionState = ConnectionState.CHECKING,
    val serverLabel: String = "",
    val status: String = "준비 중",
    val busy: Boolean = false,
    val awaitingOcr: Boolean = false,
    val lastSpokenText: String = "",
    val messages: List<ChatLine> = emptyList(),
    val errorText: String? = null,
)

class AssistantViewModel(
    private val repository: OdissRepository = OdissRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(AssistantUiState(serverLabel = repository.serverLabel))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        refreshHealth()
    }

    /** 서버 연결 상태 확인 (앱 시작 / 재시도 버튼). */
    fun refreshHealth() {
        _uiState.value = _uiState.value.copy(
            connection = ConnectionState.CHECKING,
            status = "서버 연결 확인 중",
            errorText = null,
        )
        viewModelScope.launch {
            val ok = repository.checkHealth()
            _uiState.value = _uiState.value.copy(
                connection = if (ok) ConnectionState.ONLINE else ConnectionState.OFFLINE,
                status = if (ok) "대기 중" else "서버에 연결할 수 없습니다",
                errorText = if (ok) null else "서버(${repository.serverLabel})에 연결하지 못했습니다. 주소와 네트워크를 확인해 주세요.",
            )
        }
    }

    fun onSttTextRecognized(text: String, tts: TtsController) {
        if (text.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "음성을 알아듣지 못했습니다", busy = false)
            return
        }
        appendMessage("user", text)
        _uiState.value = _uiState.value.copy(
            status = "서버 응답을 기다리는 중",
            busy = true,
            errorText = null,
        )
        viewModelScope.launch {
            runCatching { repository.sendSttLog(text) }
            repository.sendStt(text)
                .catch { e -> onStreamError(e.message) }
                .collect { response -> handleServerMessage(response, tts) }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    fun sendOcrPayload(rawText: String, medications: List<MedicationInput>, tts: TtsController) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = "처방전 정보를 서버로 보내는 중", busy = true, errorText = null)
            repository.sendOcrResult(rawText, medications, confidence = 0.8)
                .catch { e -> onStreamError(e.message) }
                .collect { response -> handleServerMessage(response, tts) }
            runCatching { repository.submitOcr(rawText, medications, confidence = 0.8) }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    /** "다시 듣기" — 마지막 음성 응답을 다시 재생. */
    fun repeatLast(tts: TtsController) {
        val last = _uiState.value.lastSpokenText
        if (last.isNotBlank()) {
            tts.speak(last)
        }
    }

    private fun onStreamError(message: String?) {
        _uiState.value = _uiState.value.copy(
            connection = ConnectionState.OFFLINE,
            status = "연결이 끊어졌습니다",
            busy = false,
            errorText = "통신 오류: ${message ?: "알 수 없는 오류"}. 다시 시도해 주세요.",
        )
    }

    private fun handleServerMessage(response: WsResponse, tts: TtsController) {
        val spoken = (response.response_text ?: response.text ?: response.message).orEmpty()
        if (spoken.isNotBlank() && response.type != "pong") {
            appendMessage("odiss", spoken)
        }
        // 서버와 정상적으로 메시지를 주고받았으므로 연결 상태를 온라인으로 확정.
        if (response.type != "error") {
            _uiState.value = _uiState.value.copy(connection = ConnectionState.ONLINE)
        }
        when (response.type) {
            "filler" -> {
                _uiState.value = _uiState.value.copy(status = "생각하는 중")
                if (response.requires_tts) speakAndRemember(spoken, tts)
            }

            "response", "identity_check", "reminder", "ocr_processed" -> {
                _uiState.value = _uiState.value.copy(status = "응답 완료")
                if (response.requires_tts) speakAndRemember(spoken, tts)
            }

            "ocr_request" -> {
                _uiState.value = _uiState.value.copy(status = "처방전 촬영이 필요합니다", awaitingOcr = true)
                if (response.requires_tts) speakAndRemember(spoken, tts)
            }

            "pong" -> _uiState.value = _uiState.value.copy(status = "연결됨")
            "session_closed" -> _uiState.value = _uiState.value.copy(status = "세션 종료")
            "ignored" -> _uiState.value = _uiState.value.copy(status = "무시된 발화")
            "error" -> _uiState.value = _uiState.value.copy(
                status = "오류",
                errorText = response.message ?: "서버 오류가 발생했습니다",
            )
        }
    }

    private fun speakAndRemember(text: String, tts: TtsController) {
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(lastSpokenText = text)
        tts.speak(text)
    }

    fun clearAwaitingOcr() {
        _uiState.value = _uiState.value.copy(awaitingOcr = false)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorText = null)
    }

    private fun appendMessage(role: String, text: String) {
        val line = ChatLine(role, text)
        _uiState.value = _uiState.value.copy(
            messages = (_uiState.value.messages + line).takeLast(30),
        )
    }

    fun registerPushToken(token: String) {
        viewModelScope.launch {
            runCatching { repository.registerDevice(token) }
        }
    }
}
