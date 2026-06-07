package com.odiss.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.odiss.assistant.MainActivity
import com.odiss.assistant.audio.TtsController
import com.odiss.assistant.capture.CaptureActivity
import com.odiss.assistant.core.AssistantPreferences
import com.odiss.assistant.data.OdissRepository
import com.odiss.assistant.model.WsResponse
import com.odiss.assistant.voice.VoiceIntent
import com.odiss.assistant.voice.VoiceIntentClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

/**
 * 시리/빅스비 형태의 상시 음성비서를 구동하는 포그라운드 서비스.
 *
 * - 알림이 떠 있는 동안 앱 UI를 닫아도 계속 동작한다(핸즈프리).
 * - MediaRecorder로 짧게 녹음한 뒤 ai-server Gemini STT로 전사한다.
 * - 질의는 WebSocket(/ws/chat)으로 보내고, 응답은 TTS로 자동 재생한다.
 * - 응답 후 짧은 연속 대화 창을 열어 추가 발화를 받는다(tts_plus_followup).
 * - 촬영 요청/명령 시 CaptureActivity 를 띄워 사용자 허가 후 촬영→OCR 한다.
 */
class HandsFreeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repository by lazy { OdissRepository() }
    private val tts by lazy { TtsController(this) }
    private val prefs by lazy { AssistantPreferences(this) }

    private var listening = false
    private var processing = false
    private var destroyed = false

    /** 연속 대화 창이 열려 있는 시각(elapsedRealtime 기준). */
    private var activeConversationUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        startForegroundSafely()

        if (!hasMicPermission()) {
            tts.speak("마이크 권한이 없어 핸즈프리를 시작할 수 없습니다. 앱에서 권한을 허용해 주세요.")
            stopSelfClean()
            return START_NOT_STICKY
        }
        prefs.handsFreeEnabled = true
        startListening()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildOngoingNotification("핸즈프리 대기 중"), type)
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    // region 음성 인식 루프

    private fun startListening() {
        if (destroyed || processing || listening) return
        if (!prefs.handsFreeEnabled) return
        if (!hasMicPermission()) {
            stopSelfClean()
            return
        }
        listening = true
        updateNotification("녹음 중")
        scope.launch(Dispatchers.IO) {
            val audio = runCatching { recordAudioOnce() }.getOrNull()
            if (audio == null) {
                listening = false
                scheduleRestart(1000)
                return@launch
            }
            listening = false
            updateNotification("Gemini 음성 인식 중")
            val text = runCatching { repository.transcribeAudio(audio) }
                .onFailure { Log.w(TAG, "Gemini STT failed: ${it.message}") }
                .getOrDefault("")
                .trim()
            runCatching { audio.delete() }
            handler.post {
                if (text.isBlank()) {
                    scheduleRestart(400)
                } else {
                    handleTranscript(text)
                }
            }
        }
    }

    private fun stopListening() {
        listening = false
    }

    private fun recordAudioOnce(): File {
        val output = File.createTempFile("odiss-handsfree-", ".m4a", cacheDir)
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(16_000)
            setOutputFile(output.absolutePath)
            prepare()
        }
        try {
            recorder.start()
            Thread.sleep(RECORDING_WINDOW_MS)
            recorder.stop()
        } finally {
            recorder.release()
        }
        return output
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({
            listening = false
            startListening()
        }, delayMs)
    }

    // endregion

    private fun handleTranscript(text: String) {
        if (destroyed) return
        // 핸즈프리 ON 상태에서는 사용자가 매번 버튼/웨이크워드를 말하지 않아도
        // 실제 자동 대화로 이어지게 한다. 웨이크워드는 여전히 "네, 말씀하세요" 흐름에 사용된다.
        val active = prefs.handsFreeEnabled || SystemClock.elapsedRealtime() < activeConversationUntil
        when (VoiceIntentClassifier.classify(text, active)) {
            VoiceIntent.NONE -> startListening()

            VoiceIntent.WAKE_ONLY -> {
                processing = true
                updateNotification("듣고 있어요")
                tts.speak("네, 말씀하세요.") {
                    markActiveConversation()
                    processing = false
                    startListening()
                }
            }

            VoiceIntent.CAPTURE -> {
                processing = true
                updateNotification("약 사진 촬영")
                tts.speak("약 사진을 촬영할게요. 화면에서 확인해 주세요.") {
                    launchCapture()
                    processing = false
                    // 촬영 화면이 떠 있는 동안엔 잠시 대기 후 다시 듣는다.
                    scheduleRestart(8000)
                }
            }

            VoiceIntent.QUERY -> {
                val query = VoiceIntentClassifier.stripWakeWord(text).ifBlank { text }
                sendQuery(query)
            }
        }
    }

    private fun sendQuery(query: String) {
        processing = true
        updateNotification("생각하는 중")
        var resumeScheduled = false
        scope.launch {
            runCatching { repository.sendSttLog(query) }
            repository.sendStt(query)
                .catch { e ->
                    Log.w(TAG, "ws error: ${e.message}")
                    tts.speak("서버와 연결이 어렵습니다. 잠시 후 다시 말씀해 주세요.") {
                        processing = false
                        startListening()
                    }
                    resumeScheduled = true
                }
                .collect { response ->
                    if (handleResponse(response)) resumeScheduled = true
                }
            if (!resumeScheduled) {
                processing = false
                startListening()
            }
        }
    }

    /** @return 응답 처리 중 TTS 완료 콜백으로 듣기 재개를 예약했으면 true. */
    private fun handleResponse(response: WsResponse): Boolean {
        val spoken = (response.response_text ?: response.text ?: response.message).orEmpty()
        return when (response.type) {
            "filler" -> {
                if (response.requires_tts && spoken.isNotBlank()) tts.speak(spoken)
                false
            }

            "ocr_request" -> {
                updateNotification("처방전 촬영 필요")
                val say = spoken.ifBlank { "처방전을 촬영해 주세요." }
                tts.speak(say) {
                    launchCapture()
                    processing = false
                    scheduleRestart(8000)
                }
                true
            }

            "response", "identity_check", "reminder", "ocr_processed" -> {
                markActiveConversation()
                updateNotification("핸즈프리 대기 중")
                val say = spoken.ifBlank { "" }
                if (response.requires_tts && say.isNotBlank()) {
                    tts.speak(say) {
                        processing = false
                        startListening()
                    }
                    true
                } else {
                    false
                }
            }

            "error" -> {
                tts.speak(spoken.ifBlank { "오류가 발생했습니다." }) {
                    processing = false
                    startListening()
                }
                true
            }

            else -> false
        }
    }

    private fun markActiveConversation() {
        activeConversationUntil = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
    }

    private fun launchCapture() {
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "direct startActivity blocked: ${it.message}") }
        postCaptureNotification(intent)
    }

    // region 알림

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "핸즈프리 상태",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "핸즈프리 음성비서가 동작 중임을 표시합니다." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                "촬영 요청",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "처방전/약 촬영이 필요할 때 알립니다." },
        )
    }

    private fun buildOngoingNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HandsFreeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("ODISS 핸즈프리")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "끄기", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        if (destroyed) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildOngoingNotification(status))
        }
    }

    private fun postCaptureNotification(captureIntent: Intent) {
        val pending = PendingIntent.getActivity(
            this,
            2,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_CAPTURE)
            .setContentTitle("처방전 촬영")
            .setContentText("눌러서 약/처방전을 촬영해 주세요.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(CAPTURE_NOTIF_ID, notification)
        }
    }

    // endregion

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun stopSelfClean() {
        prefs.handsFreeEnabled = false
        destroyed = true
        stopListening()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        stopListening()
        runCatching { tts.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HandsFreeService"
        private const val NOTIF_ID = 4201
        private const val CAPTURE_NOTIF_ID = 4202
        private const val CHANNEL_ONGOING = "odiss_handsfree"
        private const val CHANNEL_CAPTURE = "odiss_capture"
        private const val CONVERSATION_WINDOW_MS = 12_000L
        private const val RECORDING_WINDOW_MS = 4_500L

        const val ACTION_STOP = "com.odiss.assistant.action.STOP_HANDSFREE"

        fun start(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
