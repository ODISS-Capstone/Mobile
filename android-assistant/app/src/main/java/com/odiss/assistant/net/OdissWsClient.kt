package com.odiss.assistant.net

import com.odiss.assistant.model.WsRequest
import com.odiss.assistant.model.WsResponse
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class OdissWsClient(
    private val baseWsUrl: String,
    private val wsToken: String,
    private val speakerId: String,
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter: JsonAdapter<WsRequest> = moshi.adapter(WsRequest::class.java)
    private val responseAdapter: JsonAdapter<WsResponse> = moshi.adapter(WsResponse::class.java)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val turnMutex = Mutex()
    private val inbound = Channel<WsResponse>(Channel.UNLIMITED)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connectionReady: CompletableDeferred<Unit>? = null

    /**
     * 서버가 임의 시점에 push하는 메시지(복약 알림 등) 핸들러.
     * 턴 진행 중이 아닐 때 도착한 reminder가 채널에 묻히지 않도록 즉시 전달한다.
     */
    @Volatile var onPushMessage: ((WsResponse) -> Unit)? = null

    /** 유휴 상태에서도 서버 push(알림)를 받을 수 있게 소켓을 미리 연결한다. */
    fun ensureConnected() {
        connectIfNeeded()
    }

    fun sendStt(text: String): Flow<WsResponse> = sendTurn(
        request = WsRequest(
            type = "stt_result",
            text = text,
            speaker_id = speakerId,
        ),
        terminalTypes = setOf("response", "ocr_request", "error", "identity_check", "ignored", "session_closed"),
    )

    fun sendCompanionPrompt(text: String): Flow<WsResponse> = sendTurn(
        request = WsRequest(
            type = "companion_prompt",
            text = text,
            speaker_id = speakerId,
        ),
        terminalTypes = setOf("response", "error", "session_closed"),
    )

    fun sendOcrResult(payload: Map<String, Any?>): Flow<WsResponse> = sendTurn(
        request = WsRequest(
            type = "ocr_result",
            speaker_id = speakerId,
            data = payload,
        ),
        terminalTypes = setOf("ocr_processed", "ocr_request", "error"),
    )

    private fun sendTurn(
        request: WsRequest,
        terminalTypes: Set<String>,
    ): Flow<WsResponse> = flow {
        turnMutex.withLock {
            val ready = connectIfNeeded()
            val connected = runCatching { ready.await() }.isSuccess
            if (!connected) {
                emit(WsResponse(type = "error", message = "websocket_connect_failed", requires_tts = false))
                resetSocket()
                return@withLock
            }

            val sent = webSocket?.send(requestAdapter.toJson(request)) == true
            if (!sent) {
                emit(WsResponse(type = "error", message = "websocket_send_failed", requires_tts = false))
                resetSocket()
                return@withLock
            }

            while (true) {
                val response = withTimeoutOrNull(TURN_TIMEOUT_MS) { inbound.receive() }
                    ?: WsResponse(type = "error", message = "websocket_turn_timeout", requires_tts = false)
                emit(response)
                if (response.type in terminalTypes) break
            }
        }
    }

    private fun buildRequest(): Request {
        val wsUrl = if (wsToken.isBlank()) {
            baseWsUrl
        } else {
            val delimiter = if (baseWsUrl.contains("?")) "&" else "?"
            "$baseWsUrl${delimiter}token=$wsToken"
        }
        return Request.Builder()
            .url(wsUrl)
            .apply {
                if (wsToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer $wsToken")
                }
            }
            .build()
    }

    private fun connectIfNeeded(): CompletableDeferred<Unit> = synchronized(this) {
        val existingReady = connectionReady
        if (webSocket != null && existingReady != null && existingReady.isCompleted && !existingReady.isCancelled) {
            return@synchronized existingReady
        }

        val ready = CompletableDeferred<Unit>()
        connectionReady = ready
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ready.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = responseAdapter.fromJson(text) ?: WsResponse(type = "error", message = "invalid_json")
                // 서버 주도 push(예약된 복약 알림)는 턴 응답 스트림과 분리해 즉시 처리한다.
                if (parsed.type == "reminder") {
                    val handler = onPushMessage
                    if (handler != null) {
                        handler(parsed)
                        return
                    }
                }
                inbound.trySend(parsed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.completeExceptionally(t)
                inbound.trySend(
                    WsResponse(
                        type = "error",
                        message = t.message ?: "websocket_failure",
                        requires_tts = false,
                    ),
                )
                clearSocket(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clearSocket(webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                clearSocket(webSocket)
            }
        }
        webSocket = client.newWebSocket(buildRequest(), listener)
        ready
    }

    private fun clearSocket(closedSocket: WebSocket) = synchronized(this) {
        if (webSocket == closedSocket) {
            webSocket = null
            connectionReady = null
        }
    }

    private fun resetSocket() = synchronized(this) {
        webSocket?.cancel()
        webSocket = null
        connectionReady = null
    }

    companion object {
        private const val TURN_TIMEOUT_MS = 30_000L
            }
}
