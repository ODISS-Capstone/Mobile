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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ConnectionState { CHECKING, ONLINE, OFFLINE }

data class ChatLine(val role: String, val text: String)

data class AssistantUiState(
    val connection: ConnectionState = ConnectionState.CHECKING,
    val serverLabel: String = "",
    val status: String = "준비 중",
    val busy: Boolean = false,
    val speaking: Boolean = false,
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
    private var reconnectJob: Job? = null

    init {
        refreshHealth()
    }

    /** 서버 연결 상태 확인 (앱 시작 / 재시도 버튼 / 자동 재연결). */
    fun refreshHealth() {
        checkHealth(showChecking = true)
    }

    private fun checkHealth(showChecking: Boolean) {
        if (showChecking) {
            _uiState.value = _uiState.value.copy(
                connection = ConnectionState.CHECKING,
                status = "서버 연결 확인 중",
                errorText = null,
            )
        }
        viewModelScope.launch {
            val ok = repository.checkHealth()
            onHealthResult(ok)
        }
    }

    private fun onHealthResult(ok: Boolean) {
        _uiState.value = _uiState.value.copy(
            connection = if (ok) ConnectionState.ONLINE else ConnectionState.OFFLINE,
            status = if (ok) "대기 중" else "서버 재연결 시도 중",
            errorText = if (ok) null else "서버(${repository.serverLabel})에 연결하지 못했습니다. 자동으로 다시 연결을 시도합니다.",
        )
        if (ok) {
            reconnectJob?.cancel()
            reconnectJob = null
        } else {
            startAutoReconnect()
        }
    }

    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var attempt = 1
            while (true) {
                delay(RECONNECT_INTERVAL_MS)
                _uiState.value = _uiState.value.copy(
                    connection = ConnectionState.CHECKING,
                    status = "자동 재연결 시도 중 ($attempt)",
                )
                val ok = repository.checkHealth()
                if (ok) {
                    onHealthResult(true)
                    break
                }
                _uiState.value = _uiState.value.copy(
                    connection = ConnectionState.OFFLINE,
                    status = "서버 재연결 대기 중",
                    errorText = "서버(${repository.serverLabel})에 연결하지 못했습니다. 자동으로 다시 연결을 시도합니다.",
                )
                attempt++
            }
        }
    }

    fun onSttTextRecognized(text: String, tts: TtsController) {
        if (text.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "듣는 중", busy = false)
            return
        }
        appendMessage("user", text)
        _uiState.value = _uiState.value.copy(
            status = "서버 응답을 기다리는 중",
            busy = true,
            speaking = false,
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
            status = "연결이 끊어졌습니다 - 자동 재연결 중",
            busy = false,
            speaking = false,
            errorText = "통신 오류: ${message ?: "알 수 없는 오류"}. 자동으로 다시 연결을 시도합니다.",
        )
        startAutoReconnect()
    }

    private fun handleServerMessage(response: WsResponse, tts: TtsController) {
        val spoken = (response.response_text ?: response.text ?: response.message).orEmpty()
        if (spoken.isNotBlank() && response.type != "pong") {
            appendMessage("odiss", spoken)
        }
        // 서버와 정상적으로 메시지를 주고받았으므로 연결 상태를 온라인으로 확정.
        if (response.type != "error") {
            _uiState.value = _uiState.value.copy(connection = ConnectionState.ONLINE)
            reconnectJob?.cancel()
            reconnectJob = null
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
        _uiState.value = _uiState.value.copy(lastSpokenText = text, speaking = true)
        tts.speak(text) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(speaking = false)
            }
        }
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

    companion object {
        private const val RECONNECT_INTERVAL_MS = 5_000L
    }
}
