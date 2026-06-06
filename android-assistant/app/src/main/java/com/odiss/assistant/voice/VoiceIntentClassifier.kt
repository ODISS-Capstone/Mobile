package com.odiss.assistant.voice

/**
 * 상시 음성인식 결과(한 발화)를 의도로 분류하는 순수 로직.
 *
 * - WAKE_ONLY: 웨이크워드만 말한 경우(예: "오디스") → 안내 후 대기.
 * - CAPTURE  : 촬영 명령(예: "오디스 약 찍어줘" / 대화 중 "사진 찍어").
 * - QUERY    : 웨이크워드 뒤 질의 또는 연속 대화 중 발화.
 * - NONE     : 무시할 잡음/무관 발화.
 *
 * 안드로이드 프레임워크에 의존하지 않으므로 JVM 단위 테스트가 가능하다.
 */
enum class VoiceIntent { WAKE_ONLY, CAPTURE, QUERY, NONE }

object VoiceIntentClassifier {
    private val wakeWords = listOf(
        "오디스", "오디스야", "오디써", "오디씨", "오디서", "odiss", "오디스님",
    )
    private val captureWords = listOf(
        "약 찍어", "약찍어", "사진 찍어", "사진찍어", "처방전 찍어", "처방전찍어",
        "약 촬영", "사진 촬영", "촬영해", "찍어 줘", "찍어줘", "약 보여줄게",
        "약 사진", "처방전 사진",
    )

    private fun normalize(raw: String): String = raw.trim().lowercase()

    fun hasWakeWord(raw: String): Boolean {
        val text = normalize(raw)
        return wakeWords.any { text.contains(it.lowercase()) }
    }

    fun isCaptureCommand(raw: String): Boolean {
        val text = normalize(raw)
        return captureWords.any { text.contains(it.lowercase()) }
    }

    /** 웨이크워드를 제거한 나머지 발화(질의 본문). 긴 표현부터 제거해 잔여 음절을 막는다. */
    fun stripWakeWord(raw: String): String {
        var result = raw.trim()
        for (word in wakeWords.sortedByDescending { it.length }) {
            result = result.replace(word, "", ignoreCase = true)
        }
        return result.trim().trim(',', '.', '!', '?', ' ')
    }

    /**
     * @param activeConversation 직전 응답 직후의 연속 대화 창이 열려 있는지 여부.
     */
    fun classify(raw: String, activeConversation: Boolean): VoiceIntent {
        if (raw.isBlank()) return VoiceIntent.NONE
        val wake = hasWakeWord(raw)
        val capture = isCaptureCommand(raw)

        if (capture && (wake || activeConversation)) return VoiceIntent.CAPTURE

        if (wake) {
            val body = stripWakeWord(raw)
            return if (body.isBlank()) VoiceIntent.WAKE_ONLY else VoiceIntent.QUERY
        }

        if (activeConversation) {
            // 대화 중에는 촬영 명령이 단독으로 와도 촬영으로 처리.
            if (capture) return VoiceIntent.CAPTURE
            return VoiceIntent.QUERY
        }

        return VoiceIntent.NONE
    }
}
