package com.odiss.assistant.data

import com.odiss.assistant.BuildConfig
import com.odiss.assistant.core.DeviceIdentity
import com.odiss.assistant.model.DeviceRegisterRequest
import com.odiss.assistant.model.MedicationInput
import com.odiss.assistant.model.OcrImageAnalyzeResponse
import com.odiss.assistant.model.OcrPayloadMapper
import com.odiss.assistant.model.SttTranscript
import com.odiss.assistant.model.WsResponse
import com.odiss.assistant.net.OdissApiService
import com.odiss.assistant.net.OdissWsClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class OdissRepository(
    private val httpBaseUrl: String = BuildConfig.ODISS_HTTP_BASE_URL,
    private val wsBaseUrl: String = BuildConfig.ODISS_WS_BASE_URL,
    private val wsToken: String = BuildConfig.ODISS_WS_TOKEN,
    private val speakerId: String = DeviceIdentity.speakerId,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val retrofit = Retrofit.Builder()
        .baseUrl(httpBaseUrl)
        .client(httpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
    private val api = retrofit.create(OdissApiService::class.java)
    private val wsClient = OdissWsClient(
        baseWsUrl = wsBaseUrl,
        wsToken = wsToken,
        speakerId = speakerId,
    )

    val serverLabel: String get() = httpBaseUrl

    /** 앱 시작 시 서버 연결성 확인. 성공 시 true. */
    suspend fun checkHealth(): Boolean = runCatching {
        api.health().isSuccessful
    }.getOrDefault(false)

    fun sendStt(text: String): Flow<WsResponse> = wsClient.sendStt(text)

    fun sendCompanionPrompt(text: String): Flow<WsResponse> = wsClient.sendCompanionPrompt(text)

    suspend fun transcribeAudioDebug(file: File): SttTranscript {
        val audioBody = file.asRequestBody("audio/mp4".toMediaType())
        val part = MultipartBody.Part.createFormData("file", file.name, audioBody)
        val speaker = speakerId.toRequestBody("text/plain".toMediaType())
        val language = "ko-KR".toRequestBody("text/plain".toMediaType())
        val response = api.transcribeAudio(part, speaker, language)
        if (!response.isSuccessful) {
            error("STT failed: ${response.code()}")
        }
        val body = response.body()
        return SttTranscript(
            text = body?.text.orEmpty().trim(),
            provider = body?.provider.orEmpty().ifBlank { "unknown" },
            model = body?.model.orEmpty(),
            audioBytes = body?.audio_bytes ?: 0,
        )
    }

    suspend fun transcribeAudio(file: File): String = transcribeAudioDebug(file).text

    suspend fun analyzeOcrImage(file: File): OcrImageAnalyzeResponse {
        val imageBody = file.asRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", file.name, imageBody)
        val speaker = speakerId.toRequestBody("text/plain".toMediaType())
        val response = api.analyzeOcrImage(part, speaker)
        if (!response.isSuccessful) {
            error("Server OCR failed: ${response.code()}")
        }
        return response.body() ?: OcrImageAnalyzeResponse(success = false, message = "서버 OCR 응답이 비어 있습니다.")
    }

    fun sendOcrResult(
        rawText: String,
        medications: List<MedicationInput>,
        confidence: Double,
    ): Flow<WsResponse> = wsClient.sendOcrResult(
        OcrPayloadMapper.toWsData(rawText, medications, confidence, speakerId),
    )

    suspend fun submitOcr(rawText: String, medications: List<MedicationInput>, confidence: Double) {
        api.submitOcr(
            OcrPayloadMapper.toHttpRequest(rawText, medications, confidence, speakerId),
        )
    }

    suspend fun registerDevice(pushToken: String) {
        val stableDeviceId = "android-$speakerId"
        api.registerDevice(
            DeviceRegisterRequest(
                device_id = stableDeviceId,
                speaker_id = speakerId,
                push_token = pushToken,
                app_version = BuildConfig.VERSION_NAME,
            ),
        )
    }

    suspend fun sendSttLog(text: String) {
        api.sendSttLog(
            mapOf(
                "text" to text,
                "source" to "android_stt",
                "speaker_id" to speakerId,
            ),
        )
    }
}
