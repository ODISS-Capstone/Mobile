package com.odiss.assistant

import android.app.Application
import com.odiss.assistant.core.DeviceIdentity

/**
 * 앱 프로세스 시작 시 영구 사용자 키(speaker_id)를 보장한다.
 * 어떤 Activity/Service/요청보다 먼저 onCreate가 실행되므로,
 * 이후 모든 서버 통신이 동일한 영구 키를 사용한다.
 */
class OdissApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.ensure(this)
    }
}
