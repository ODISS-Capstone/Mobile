package com.odiss.assistant.core

import android.content.Context
import android.util.Log
import com.odiss.assistant.BuildConfig
import java.util.UUID

/**
 * 설치(기기)별 영구 사용자 키(speaker_id)를 관리한다.
 *
 * 음성 스피커/기기마다 사용자가 달라지므로 영구 키 보유를 의무화한다.
 * 최초 1회 UUID를 생성해 SharedPreferences에 영구 저장하고 이후 항상 그 값을 사용한다.
 * 데모용으로 빌드에서 명시적으로 ODISS_SPEAKER_ID를 지정한 경우(기본값 android_default 제외)에는
 * 그 값을 시드로 사용한다.
 */
object DeviceIdentity {

    @Volatile
    private var cached: String? = null

    /**
     * 영구 키를 보장(없으면 생성·저장)하고 반환한다. Application.onCreate에서 호출해
     * 어떤 요청보다 먼저 키가 존재하도록 한다.
     */
    fun ensure(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_SPEAKER_ID, null)
            val id = if (!stored.isNullOrBlank()) {
                stored
            } else {
                val seed = BuildConfig.ODISS_SPEAKER_ID
                val generated = if (seed.isNotBlank() && seed != DEFAULT_SEED) {
                    seed
                } else {
                    "android-" + UUID.randomUUID().toString().replace("-", "").take(12)
                }
                prefs.edit().putString(KEY_SPEAKER_ID, generated).apply()
                Log.i(TAG, "issued permanent speaker_id=$generated")
                generated
            }
            cached = id
            return id
        }
    }

    /**
     * 현재 영구 키. ensure()가 먼저 호출되었다면 영구 저장값을 반환한다.
     * 초기화 전 접근 시에는 빌드 기본값으로 폴백한다(앱 정상 경로에서는 Application onCreate가 선행).
     */
    val speakerId: String
        get() = cached ?: BuildConfig.ODISS_SPEAKER_ID

    private const val TAG = "DeviceIdentity"
    private const val PREFS_NAME = "odiss_identity_prefs"
    private const val KEY_SPEAKER_ID = "speaker_id"
    private const val DEFAULT_SEED = "android_default"
}
