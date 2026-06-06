package com.odiss.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntentClassifierTest {

    @Test
    fun blankIsNone() {
        assertEquals(VoiceIntent.NONE, VoiceIntentClassifier.classify("   ", activeConversation = false))
    }

    @Test
    fun wakeWordOnlyReturnsWakeOnly() {
        assertEquals(VoiceIntent.WAKE_ONLY, VoiceIntentClassifier.classify("오디스", activeConversation = false))
        assertEquals(VoiceIntent.WAKE_ONLY, VoiceIntentClassifier.classify("오디스야", activeConversation = false))
    }

    @Test
    fun wakeWordWithBodyIsQuery() {
        assertEquals(
            VoiceIntent.QUERY,
            VoiceIntentClassifier.classify("오디스 오늘 약 먹어야 해?", activeConversation = false),
        )
    }

    @Test
    fun wakeWordWithCaptureCommandIsCapture() {
        assertEquals(
            VoiceIntent.CAPTURE,
            VoiceIntentClassifier.classify("오디스 약 찍어줘", activeConversation = false),
        )
    }

    @Test
    fun captureCommandWithoutWakeIgnoredWhenInactive() {
        // 대화 창이 닫혀 있으면 웨이크워드 없는 명령은 오인식을 막기 위해 무시.
        assertEquals(
            VoiceIntent.NONE,
            VoiceIntentClassifier.classify("사진 찍어", activeConversation = false),
        )
    }

    @Test
    fun captureCommandTriggersWhenConversationActive() {
        assertEquals(
            VoiceIntent.CAPTURE,
            VoiceIntentClassifier.classify("약 찍어줘", activeConversation = true),
        )
    }

    @Test
    fun plainSpeechDuringConversationIsQuery() {
        assertEquals(
            VoiceIntent.QUERY,
            VoiceIntentClassifier.classify("응 알겠어 고마워", activeConversation = true),
        )
    }

    @Test
    fun plainSpeechWithoutWakeIsNone() {
        assertEquals(
            VoiceIntent.NONE,
            VoiceIntentClassifier.classify("오늘 날씨 좋네", activeConversation = false),
        )
    }

    @Test
    fun stripWakeWordRemovesPrefix() {
        assertEquals("오늘 약 먹어야 해", VoiceIntentClassifier.stripWakeWord("오디스 오늘 약 먹어야 해"))
    }

    @Test
    fun wakeWordDetection() {
        assertTrue(VoiceIntentClassifier.hasWakeWord("오디스 안녕"))
        assertTrue(VoiceIntentClassifier.hasWakeWord("ODISS hello"))
        assertFalse(VoiceIntentClassifier.hasWakeWord("안녕하세요"))
    }
}
