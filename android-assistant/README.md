# ODISS Android Assistant (MVP)

ODISS `ai-server`의 `/ws/chat` 계약을 그대로 사용하는 Android 음성 어시스턴트 앱입니다.

## 구현 범위

- STT -> WebSocket `/ws/chat` -> filler/response TTS 재생
- `ocr_request` 수신 시 OCR 이미지 선택 플로우
- ML Kit OCR 텍스트 추출 + `ocr_result` WebSocket 전송
- `/api/ocr/analyze` 동기화 호출
- FCM 토큰 수집 후 `/api/devices/register` 등록
- FCM 수신 알림 표시

## 디렉토리

- `app/src/main/java/com/odiss/assistant/net/OdissWsClient.kt`
- `app/src/main/java/com/odiss/assistant/data/OdissRepository.kt`
- `app/src/main/java/com/odiss/assistant/ui/AssistantScreen.kt`
- `app/src/main/java/com/odiss/assistant/push/OdissFirebaseMessagingService.kt`

## 서버 사전 조건

`ai-server/.env`에 다음 값을 설정하세요.

```bash
WEBSOCKET_AUTH_TOKEN=your_ws_token
FCM_SERVER_KEY=your_legacy_server_key
```

토큰을 쓰지 않으면 `WEBSOCKET_AUTH_TOKEN`은 비워둘 수 있습니다.

## 개발 실행

```bash
cd "/home/jepetolee/PycharmProjects/Capstone-Project/android-assistant"
./gradlew :app:assembleDebug
```

에뮬레이터 기준 서버 주소는 `10.0.2.2:8000`입니다.

## APK 배포 (Phase 4)

### 1) 디버그 APK (sideload)

```bash
cd "/home/jepetolee/PycharmProjects/Capstone-Project/android-assistant"
./gradlew :app:assembleDebug
adb install -r "app/build/outputs/apk/debug/app-debug.apk"
```

### 2) 릴리즈 APK 서명

키 생성:

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore "odiss-release.keystore" -alias odiss -keyalg RSA -keysize 2048 -validity 10000
```

릴리즈 빌드:

```bash
cd "/home/jepetolee/PycharmProjects/Capstone-Project/android-assistant"
./gradlew :app:assembleRelease
```

APK 경로:

`/home/jepetolee/PycharmProjects/Capstone-Project/android-assistant/app/build/outputs/apk/release/app-release.apk`
