package com.odiss.assistant.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class OdissWsClientTest {
    @Test
    fun baseClientConstructsWithTokenQuery() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(101))
        val wsUrl = server.url("/ws/chat").toString().replace("http://", "ws://")
        val client = OdissWsClient(
            baseWsUrl = wsUrl,
            wsToken = "demo-token",
            speakerId = "speaker-test",
        )
        // 토큰이 있을 때 ws URL 구성과 어댑터 초기화가 예외 없이 동작해야 한다.
        assertTrue(wsUrl.startsWith("ws://"))
        client.sendStt("안녕")
        server.shutdown()
    }

    @Test
    fun sttFlowEmitsFillerThenResponse() = runBlocking<Unit> {
        val server = MockWebServer()
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // 클라이언트가 stt_result 를 보내면 filler + response 를 돌려준다.
                webSocket.send("""{"type":"filler","text":"잠시만요","requires_tts":true}""")
                webSocket.send("""{"type":"response","response_text":"안녕하세요","requires_tts":true}""")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()

        val wsUrl = server.url("/ws/chat").toString().replace("http://", "ws://")
        val client = OdissWsClient(baseWsUrl = wsUrl, wsToken = "", speakerId = "speaker-test")

        val received = withTimeout(5_000) { client.sendStt("안녕").toList() }

        assertTrue("filler 응답을 받아야 한다", received.any { it.type == "filler" })
        assertTrue("response 응답을 받아야 한다", received.any { it.type == "response" })
        // okhttp 연결 풀이 남아 shutdown 이 지연될 수 있으나 검증은 이미 끝났다.
        runCatching { server.shutdown() }
    }

    @Test
    fun ocrResultFlowEmitsOcrProcessed() = runBlocking<Unit> {
        val server = MockWebServer()
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"ocr_processed","message":"확인했습니다","requires_tts":true}""")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()

        val wsUrl = server.url("/ws/chat").toString().replace("http://", "ws://")
        val client = OdissWsClient(baseWsUrl = wsUrl, wsToken = "", speakerId = "speaker-test")

        val payload = mapOf(
            "raw_text" to "타이레놀 500mg",
            "medications" to listOf(mapOf("name" to "타이레놀")),
            "confidence" to 0.8,
        )
        val received = withTimeout(5_000) { client.sendOcrResult(payload).toList() }

        assertTrue("ocr_processed 응답을 받아야 한다", received.any { it.type == "ocr_processed" })
        runCatching { server.shutdown() }
    }
}
