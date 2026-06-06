package com.odiss.assistant.net

import com.odiss.assistant.model.WsRequest
import com.odiss.assistant.model.WsResponse
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    fun sendStt(text: String): Flow<WsResponse> = callbackFlow {
        val wsUrl = if (wsToken.isBlank()) {
            baseWsUrl
        } else {
            val delimiter = if (baseWsUrl.contains("?")) "&" else "?"
            "$baseWsUrl${delimiter}token=$wsToken"
        }
        val request = Request.Builder()
            .url(wsUrl)
            .apply {
                if (wsToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer $wsToken")
                }
            }
            .build()

        val terminalTypes = setOf("response", "error", "identity_check", "ignored", "session_closed")
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = WsRequest(
                    type = "stt_result",
                    text = text,
                    speaker_id = speakerId,
                )
                webSocket.send(requestAdapter.toJson(payload))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = responseAdapter.fromJson(text) ?: WsResponse(type = "error", message = "invalid_json")
                trySend(parsed)
                if (parsed.type in terminalTypes) {
                    webSocket.close(1000, "turn_completed")
                    close()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(
                    WsResponse(
                        type = "error",
                        message = t.message ?: "websocket_failure",
                        requires_tts = false,
                    ),
                )
                close(t)
            }
        }

        val ws = client.newWebSocket(request, listener)
        awaitClose {
            ws.cancel()
        }
    }

    fun sendOcrResult(payload: Map<String, Any?>): Flow<WsResponse> = callbackFlow {
        val wsUrl = if (wsToken.isBlank()) {
            baseWsUrl
        } else {
            val delimiter = if (baseWsUrl.contains("?")) "&" else "?"
            "$baseWsUrl${delimiter}token=$wsToken"
        }
        val request = Request.Builder()
            .url(wsUrl)
            .apply {
                if (wsToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer $wsToken")
                }
            }
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val message = WsRequest(
                    type = "ocr_result",
                    speaker_id = speakerId,
                    data = payload,
                )
                webSocket.send(requestAdapter.toJson(message))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = responseAdapter.fromJson(text) ?: WsResponse(type = "error", message = "invalid_json")
                trySend(parsed)
                if (parsed.type in setOf("ocr_processed", "ocr_request", "error")) {
                    webSocket.close(1000, "ocr_done")
                    close()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsResponse(type = "error", message = t.message ?: "websocket_failure"))
                close(t)
            }
        }

        val ws = client.newWebSocket(request, listener)
        awaitClose {
            ws.cancel()
        }
    }
}
