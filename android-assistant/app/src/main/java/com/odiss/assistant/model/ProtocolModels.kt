package com.odiss.assistant.model

data class WsRequest(
    val type: String,
    val text: String? = null,
    val speaker_id: String? = null,
    val data: Map<String, Any?>? = null,
)

data class WsResponse(
    val type: String = "",
    val text: String? = null,
    val response_text: String? = null,
    val response_type: String? = null,
    val reason: String? = null,
    val requires_tts: Boolean = true,
    val message: String? = null,
    val stage: String? = null,
)

data class MedicationInput(
    val name: String,
    val strength: String? = null,
    val dosage: String? = null,
    val frequency: String? = null,
    val timing: String? = null,
)

data class OcrAnalyzeRequest(
    val raw_text: String,
    val medications: List<MedicationInput>,
    val confidence: Double,
    val speaker_id: String?,
)

data class OcrImageAnalyzeResponse(
    val success: Boolean = true,
    val message: String = "",
    val response_text: String = "",
    val medication_count: Int = 0,
    val raw_text: String = "",
    val needs_recapture: Boolean = false,
)

data class DeviceRegisterRequest(
    val device_id: String,
    val speaker_id: String,
    val platform: String = "android",
    val push_token: String,
    val app_version: String = "",
)

data class SttTranscribeResponse(
    val success: Boolean = true,
    val text: String = "",
    val provider: String = "unknown",
    val model: String = "",
    val audio_bytes: Int = 0,
)

data class SttTranscript(
    val text: String = "",
    val provider: String = "unknown",
    val model: String = "",
    val audioBytes: Int = 0,
)

/**
 * OCR 결과를 ai-server 계약(`/api/ocr/analyze` HTTP, `/ws/chat` ocr_result)에
 * 동일하게 매핑하는 단일 진입점.
 *
 * - HTTP `OCRResultInput` 스키마: raw_text, medications[], confidence, speaker_id
 * - WebSocket `ocr_result.data` 도 같은 평면 구조를 그대로 사용한다.
 *
 * 두 경로가 항상 같은 payload를 보내도록 mapper를 한 곳에 모은다.
 */
object OcrPayloadMapper {
    fun medicationToMap(med: MedicationInput): Map<String, Any?> = mapOf(
        "name" to med.name,
        "strength" to med.strength,
        "dosage" to med.dosage,
        "frequency" to med.frequency,
        "timing" to med.timing,
    )

    /** WebSocket `ocr_result.data` 로 보낼 평면 payload. */
    fun toWsData(
        rawText: String,
        medications: List<MedicationInput>,
        confidence: Double,
        speakerId: String?,
    ): Map<String, Any?> = mapOf(
        "raw_text" to rawText,
        "medications" to medications.map { medicationToMap(it) },
        "confidence" to confidence,
        "speaker_id" to speakerId,
    )

    /** HTTP `/api/ocr/analyze` 로 보낼 타입드 요청. */
    fun toHttpRequest(
        rawText: String,
        medications: List<MedicationInput>,
        confidence: Double,
        speakerId: String?,
    ): OcrAnalyzeRequest = OcrAnalyzeRequest(
        raw_text = rawText,
        medications = medications,
        confidence = confidence,
        speaker_id = speakerId,
    )
}
