package com.odiss.assistant.net

import com.odiss.assistant.model.DeviceRegisterRequest
import com.odiss.assistant.model.OcrAnalyzeRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface OdissApiService {
    @GET("/health")
    suspend fun health(): Response<Map<String, Any>>

    @POST("/api/ocr/analyze")
    suspend fun submitOcr(@Body payload: OcrAnalyzeRequest): Response<Map<String, Any>>

    @POST("/api/devices/register")
    suspend fun registerDevice(@Body payload: DeviceRegisterRequest): Response<Map<String, Any>>

    @POST("/api/stt/log")
    suspend fun sendSttLog(@Body payload: Map<String, Any>): Response<Map<String, Any>>
}
