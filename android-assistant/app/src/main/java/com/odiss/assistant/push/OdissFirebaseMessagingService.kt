package com.odiss.assistant.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.odiss.assistant.capture.CaptureActivity
import com.odiss.assistant.data.OdissRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class OdissFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // 토큰 갱신 시 서버에 재등록해 앱 종료 상태에서도 푸시가 도달하도록 한다.
        if (token.isBlank()) return
        scope.launch {
            runCatching { OdissRepository().registerDevice(token) }
                .onFailure { Log.w(TAG, "token re-register failed: ${it.message}") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        ensureChannels()

        val data = message.data
        val type = data["type"].orEmpty()
        val action = data["action"].orEmpty()
        if (type == "ocr_request" || action == "request_ocr") {
            launchCaptureRequest(data["message"] ?: message.notification?.body)
            return
        }

        val body = data["text"]
            ?: message.notification?.body
            ?: "복약 알림이 도착했습니다."
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ODISS")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % 100000).toInt(), notification)

        // AI 스피커 동작: WebSocket이 끊긴 상태로 도착한 알림도 음성으로 직접 안내한다.
        if (data["tts"] != "0") {
            speakOnce(body)
        }
    }

    /** 일회성 TTS. 서비스가 죽어 있어도 알림 본문을 소리내어 읽는다. */
    private fun speakOnce(text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                tts?.shutdown()
                return@TextToSpeech
            }
            tts?.language = Locale.KOREAN
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    tts?.shutdown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    tts?.shutdown()
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fcm-reminder-${System.currentTimeMillis()}")
        }
    }

    /**
     * 앱이 종료/백그라운드 상태여도 촬영모드를 띄운다.
     * Android 12+의 백그라운드 액티비티 실행 제약에 대응하기 위해 full-screen intent 알림을 사용한다.
     */
    private fun launchCaptureRequest(spokenMessage: String?) {
        val captureIntent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            spokenMessage?.let { putExtra(EXTRA_PROMPT, it) }
        }

        // 우선 직접 실행 시도(포그라운드/최근 사용 상태에서 가장 빠름).
        val directLaunch = runCatching { startActivity(captureIntent) }.isSuccess

        val pendingIntent = PendingIntent.getActivity(
            this,
            CAPTURE_REQUEST_CODE,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ODISS 촬영 요청")
            .setContentText(spokenMessage ?: "약봉투 촬영을 진행해 주세요.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (!directLaunch) {
            // 직접 실행이 막히면 잠금/백그라운드에서도 화면을 띄우는 full-screen intent로 폴백.
            builder.setFullScreenIntent(pendingIntent, true)
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(CAPTURE_NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "capture notification failed: ${it.message}") }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ODISS 알림",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        if (manager.getNotificationChannel(CAPTURE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CAPTURE_CHANNEL_ID,
                    "ODISS 촬영 요청",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "약봉투/처방전 촬영을 요청할 때 화면을 띄웁니다."
                },
            )
        }
    }

    companion object {
        private const val TAG = "OdissFcm"
        private const val CHANNEL_ID = "odiss_reminders"
        private const val CAPTURE_CHANNEL_ID = "odiss_capture_requests"
        private const val CAPTURE_NOTIFICATION_ID = 90421
        private const val CAPTURE_REQUEST_CODE = 9042
        const val EXTRA_PROMPT = "odiss_capture_prompt"
    }
}
