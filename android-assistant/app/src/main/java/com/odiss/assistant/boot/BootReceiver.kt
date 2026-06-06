package com.odiss.assistant.boot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.odiss.assistant.core.AssistantPreferences
import com.odiss.assistant.service.HandsFreeService

/**
 * 부팅 완료 시, 사용자가 인앱에서 핸즈프리를 켜둔 상태였을 때만 서비스를 다시 시작한다.
 * 켜고 끄기는 인앱 토글로만 제어하므로, 저장된 플래그를 그대로 신뢰한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        if (!AssistantPreferences(context).handsFreeEnabled) return

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) return

        HandsFreeService.start(context)
    }
}
