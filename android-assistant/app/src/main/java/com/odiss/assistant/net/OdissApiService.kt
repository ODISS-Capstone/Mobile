package com.odiss.assistant.net

import com.odiss.assistant.model.DeviceRegisterRequest
import com.odiss.assistant.model.OcrAnalyzeRequest
import com.odiss.assistant.model.OcrImageAnalyzeResponse
import com.odiss.assistant.model.SttTranscribeResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface OdissApiService {
    @GET("/health")
    suspend fun health(): Response<Map<String, Any>>

    @POST("/api/ocr/analyze")
    suspend fun submitOcr(@Body payload: OcrAnalyzeRequest): Response<Map<String, Any>>

    @Multipart
    @POST("/api/ocr/analyze-image")
    suspend fun analyzeOcrImage(
        @Part file: MultipartBody.Part,
        @Part("speaker_id") speakerId: RequestBody,
    ): Response<OcrImageAnalyzeResponse>

    @POST("/api/devices/register")
    suspend fun registerDevice(@Body payload: DeviceRegisterRequest): Response<Map<String, Any>>

    @POST("/api/stt/log")
    suspend fun sendSttLog(@Body payload: Map<String, Any>): Response<Map<String, Any>>

    @Multipart
    @POST("/api/stt/transcribe")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("speaker_id") speakerId: RequestBody,
        @Part("language") language: RequestBody,
    ): Response<SttTranscribeResponse>
}
