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
import com.odiss.assistant.audio.VoiceRecorder
import com.odiss.assistant.capture.CaptureActivity
import com.odiss.assistant.core.AssistantPreferences
import com.odiss.assistant.core.ConversationStore
import com.odiss.assistant.data.OdissRepository
import com.odiss.assistant.model.WsResponse
import com.odiss.assistant.voice.VoiceIntent
import com.odiss.assistant.voice.VoiceIntentClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 시리/빅스비 형태의 상시 음성비서를 구동하는 포그라운드 서비스.
 *
 * - 알림이 떠 있는 동안 앱 UI를 닫아도 계속 동작한다(핸즈프리).
 * - MediaRecorder로 짧게 녹음한 뒤 ai-server STT로 전사한다.
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
    private var pausedForCapture = false
    private var restartGeneration = 0
    private val audioQueue = Channel<QueuedAudio>(capacity = 8)
    private var processorStarted = false

    /** 연속 대화 창이 열려 있는 시각(elapsedRealtime 기준). */
    private var activeConversationUntil = 0L

    private data class QueuedAudio(
        val file: File,
        val activeAtRecordingStart: Boolean,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startAudioProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_FOR_CAPTURE -> {
                pausedForCapture = true
                processing = true
                listening = false
                updateNotification("사진 OCR 처리 중")
                return START_STICKY
            }
            ACTION_RESUME_AFTER_CAPTURE -> {
                pausedForCapture = false
                processing = false
                markActiveConversation()
                updateNotification("핸즈프리 대기 중")
                startListening()
                return START_STICKY
            }
            ACTION_PROCESS_CAPTURED_IMAGE -> {
                startForegroundSafely()
                intent.getStringExtra(EXTRA_IMAGE_PATH)?.let { processCapturedImage(it) }
                return START_STICKY
            }
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
        if (destroyed || pausedForCapture || processing || listening) return
        if (!prefs.handsFreeEnabled) return
        if (!hasMicPermission()) {
            stopSelfClean()
            return
        }
        restartGeneration++
        listening = true
        val activeAtRecordingStart = SystemClock.elapsedRealtime() < activeConversationUntil
        updateNotification("녹음 중")
        scope.launch(Dispatchers.IO) {
            val recording = runCatching {
                VoiceRecorder.recordUntilSilence(
                    context = this@HandsFreeService,
                    prefix = "odiss-handsfree-",
                )
            }.getOrNull()
            val audio = when (recording) {
                is VoiceRecorder.RecordingResult.Audio -> recording.file
                VoiceRecorder.RecordingResult.NoSpeech -> {
                    listening = false
                    scheduleRestart(NO_SPEECH_RETRY_MS)
                    return@launch
                }
                null -> {
                    listening = false
                    scheduleRestart(NO_SPEECH_RETRY_MS)
                    return@launch
                }
            }
            if (!audio.exists()) {
                listening = false
                scheduleRestart(NO_SPEECH_RETRY_MS)
                return@launch
            }
            listening = false
            handler.post {
                val queued = audioQueue.trySend(QueuedAudio(audio, activeAtRecordingStart)).isSuccess
                if (!queued) {
                    Log.w(TAG, "audio queue full; dropping ${audio.name}")
                    runCatching { audio.delete() }
                }
                scheduleRestart(FAST_RECORD_RESTART_MS)
            }
        }
    }

    private fun startAudioProcessor() {
        if (processorStarted) return
        processorStarted = true
        scope.launch(Dispatchers.IO) {
            for (queued in audioQueue) {
                processQueuedAudio(queued)
            }
        }
    }

    private suspend fun processQueuedAudio(queued: QueuedAudio) {
        val audio = queued.file
        if (destroyed || !audio.exists()) return
        withContext(Dispatchers.Main.immediate) {
            updateNotification("서버 음성 인식 중")
        }
        val transcript = runCatching { repository.transcribeAudioDebug(audio) }
            .onFailure { Log.w(TAG, "STT failed: ${it.message}") }
            .getOrNull()
        val text = transcript?.text.orEmpty().trim()
        Log.i(
            TAG,
            "STT provider=${transcript?.provider ?: "unknown"} model=${transcript?.model.orEmpty()} " +
                "audioBytes=${transcript?.audioBytes ?: 0} text=$text",
        )
        runCatching { audio.delete() }
        withContext(Dispatchers.Main.immediate) {
            if (text.isBlank()) {
                updateNotification("음성 인식 결과 없음")
            } else {
                updateNotification("인식: ${text.take(36)}")
                handleTranscript(text, queued.activeAtRecordingStart)
            }
        }
    }

    private fun stopListening() {
        restartGeneration++
        listening = false
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        val generation = ++restartGeneration
        handler.postDelayed({
            if (generation != restartGeneration) return@postDelayed
            if (listening || processing || pausedForCapture) return@postDelayed
            startListening()
        }, delayMs)
    }

    // endregion

    private fun handleTranscript(text: String, activeAtRecordingStart: Boolean = false) {
        if (destroyed) return
        // 호출어가 없으면 응답하지 않는다. 단, "오디스" 호출 후 follow-up 창에서는 이어서 듣는다.
        // 긴 녹음/Whisper 왕복 뒤에도 대화 중 발화가 버려지지 않도록 녹음 시작 시점도 함께 본다.
        val active = activeAtRecordingStart || SystemClock.elapsedRealtime() < activeConversationUntil
        when (VoiceIntentClassifier.classify(text, active)) {
            VoiceIntent.NONE -> startListening()

            VoiceIntent.WAKE_ONLY -> {
                // 휘발성 메모리의 신원 기반 인사("네, OO님. 말씀하세요.")는 서버 wake fast-path가 즉시 돌려준다.
                markActiveConversation()
                sendQuery(text)
            }

            VoiceIntent.CAPTURE -> {
                // 촬영 의도도 서버에 보내서 identity/대화엔진/OCR 라우팅 로그를 남기고,
                // 서버의 ocr_request 응답을 받은 뒤 카메라를 연다.
                sendQuery(text)
            }

            VoiceIntent.QUERY -> {
                val query = VoiceIntentClassifier.stripWakeWord(text).ifBlank { text }
                sendQuery(query)
            }
        }
    }

    private fun sendQuery(query: String) {
        processing = true
        markActiveConversation()
        ConversationStore.post("user", query)
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
                if (response.requires_tts && spoken.isNotBlank()) {
                    ConversationStore.post("odiss", spoken)
                    tts.speak(spoken)
                }
                false
            }

            "ocr_request" -> {
                updateNotification("처방전 촬영 필요")
                val say = spoken.ifBlank { "약 정보 확인을 위해 촬영 창을 열게요." }
                ConversationStore.post("odiss", say)
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
                    ConversationStore.post("odiss", say)
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
                val say = spoken.ifBlank { "오류가 발생했습니다." }
                ConversationStore.post("odiss", say)
                tts.speak(say) {
                    processing = false
                    startListening()
                }
                true
            }

            // wake_word_ack/smalltalk 등 그 외 타입도 읽을 텍스트가 있으면 바로 말한다.
            else -> {
                if (response.requires_tts && spoken.isNotBlank()) {
                    markActiveConversation()
                    updateNotification("핸즈프리 대기 중")
                    ConversationStore.post("odiss", spoken)
                    tts.speak(spoken) {
                        processing = false
                        startListening()
                    }
                    true
                } else {
                    false
                }
            }
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

    private fun processCapturedImage(path: String) {
        if (destroyed) return
        pausedForCapture = false
        processing = true
        updateNotification("사진 분석 중")
        scope.launch {
            val imageFile = File(path)
            val ocr = async(Dispatchers.IO) {
                runCatching { repository.analyzeOcrImage(imageFile) }
            }

            speakAndWait("사진 분석을 시작했습니다. 기다리는 동안 제가 계속 도와드릴게요.")
            speakAnalysisCompanion()

            val result = ocr.await()
            runCatching { imageFile.delete() }
            result
                .onSuccess { response ->
                    val spoken = response.response_text.ifBlank { response.message }
                        .ifBlank { "사진 분석이 완료되었습니다." }
                    ConversationStore.post("odiss", spoken)
                    speakAndWait(spoken)
                }
                .onFailure { e ->
                    Log.w(TAG, "server image OCR failed: ${e.message}")
                    val msg = "서버에서 사진을 읽지 못했습니다. 다시 촬영해 주세요."
                    ConversationStore.post("odiss", msg)
                    speakAndWait(msg)
                }

            processing = false
            markActiveConversation()
            updateNotification("핸즈프리 대기 중")
            startListening()
        }
    }

    private suspend fun speakAnalysisCompanion() {
        val prompt = "사진 OCR 분석이 진행되는 동안 환자에게 할 짧은 대기 안내를 해 주세요. 기존 대화기록과 환자 복약 맥락을 바탕으로 안심시키고, 복약 확인 질문을 한 문장만 자연스럽게 해 주세요."
        repository.sendCompanionPrompt(prompt)
            .catch { e -> Log.w(TAG, "analysis companion failed: ${e.message}") }
            .collect { response ->
                val spoken = (response.response_text ?: response.text ?: response.message).orEmpty()
                if (response.requires_tts && spoken.isNotBlank()) {
                    ConversationStore.post("odiss", spoken)
                    speakAndWait(spoken)
                }
            }
    }

    private suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                tts.speak(text) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
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
        ConversationStore.setStatus(status)
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
        private const val CONVERSATION_WINDOW_MS = 3 * 60 * 1000L
        private const val FAST_RECORD_RESTART_MS = 80L
        private const val NO_SPEECH_RETRY_MS = 900L
        private const val BLANK_STT_RETRY_MS = 900L

        const val ACTION_STOP = "com.odiss.assistant.action.STOP_HANDSFREE"
        const val ACTION_PAUSE_FOR_CAPTURE = "com.odiss.assistant.action.PAUSE_FOR_CAPTURE"
        const val ACTION_RESUME_AFTER_CAPTURE = "com.odiss.assistant.action.RESUME_AFTER_CAPTURE"
        const val ACTION_PROCESS_CAPTURED_IMAGE = "com.odiss.assistant.action.PROCESS_CAPTURED_IMAGE"
        private const val EXTRA_IMAGE_PATH = "extra_image_path"

        fun start(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun pauseForCapture(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java).apply { action = ACTION_PAUSE_FOR_CAPTURE }
            context.startService(intent)
        }

        fun resumeAfterCapture(context: Context) {
            val intent = Intent(context, HandsFreeService::class.java).apply { action = ACTION_RESUME_AFTER_CAPTURE }
            context.startService(intent)
        }

        fun processCapturedImage(context: Context, imagePath: String) {
            val intent = Intent(context, HandsFreeService::class.java).apply {
                action = ACTION_PROCESS_CAPTURED_IMAGE
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
            context.startService(intent)
        }

        fun startCaptureAnalysis(context: Context, imagePath: String) {
            val intent = Intent(context, HandsFreeService::class.java).apply {
                action = ACTION_PROCESS_CAPTURED_IMAGE
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
