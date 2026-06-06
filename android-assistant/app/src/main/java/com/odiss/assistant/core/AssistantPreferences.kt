package com.odiss.assistant.core

import android.content.Context

/**
 * 핸즈프리(상시 음성비서) on/off 상태를 저장한다.
 * 켜고 끄기는 오직 인앱 토글로만 제어하며, 부팅 재시작 여부도 이 값으로 판단한다.
 */
class AssistantPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var handsFreeEnabled: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HANDS_FREE, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "odiss_assistant_prefs"
        private const val KEY_HANDS_FREE = "hands_free_enabled"
    }
}
