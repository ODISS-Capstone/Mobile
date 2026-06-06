package com.odiss.assistant

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.google.firebase.messaging.FirebaseMessaging
import com.odiss.assistant.assistant.AssistantViewModel
import com.odiss.assistant.ui.AssistantScreen

class MainActivity : ComponentActivity() {
    private val viewModel = AssistantViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPushTokenSafely()
        setContent {
            MaterialTheme {
                Surface {
                    AssistantScreen(viewModel)
                }
            }
        }
    }

    /**
     * Firebase 설정(google-services.json)이 없는 빌드에서도 앱이 죽지 않도록
     * FCM 토큰 조회를 방어적으로 감싼다. 설정이 있으면 토큰을 서버에 등록한다.
     */
    private fun registerPushTokenSafely() {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        viewModel.registerPushToken(token)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FCM token fetch failed: ${e.message}")
                }
        }.onFailure { e ->
            Log.w(TAG, "Firebase not configured; skipping push registration: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OdissMain"
    }
}
