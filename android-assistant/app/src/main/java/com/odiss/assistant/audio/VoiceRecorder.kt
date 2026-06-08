package com.odiss.assistant.audio

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 서버 STT 업로드용 짧은 음성 녹음기.
 *
 * 고정 시간으로 자르면 사용자의 말을 중간에 끊으므로, MediaRecorder.maxAmplitude를
 * 이용해 "말 시작 후 침묵" 기준으로 종료한다.
 */
object VoiceRecorder {
    suspend fun recordUntilSilence(
        context: Context,
        prefix: String,
        maxDurationMs: Long = 20_000L,
        minDurationMs: Long = 500L,
        silenceAfterSpeechMs: Long = 700L,
        noSpeechTimeoutMs: Long = 1_600L,
        amplitudeThreshold: Int = 500,
    ): RecordingResult = withContext(Dispatchers.IO) {
        val output = File.createTempFile(prefix, ".m4a", context.cacheDir)
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(16_000)
            setOutputFile(output.absolutePath)
            prepare()
        }

        try {
            recorder.start()
            val startedAt = System.currentTimeMillis()
            var speechStarted = false
            var lastVoiceAt = startedAt
            var voiceHitCount = 0
            var quietHitCount = 0
            var noiseFloor = 0.0

            while (true) {
                Thread.sleep(100L)
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt
                val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                if (!speechStarted && amplitude > 0) {
                    noiseFloor = if (noiseFloor == 0.0) {
                        amplitude.toDouble()
                    } else {
                        noiseFloor * 0.9 + amplitude * 0.1
                    }
                }
                val adaptiveThreshold = maxOf(
                    amplitudeThreshold,
                    (noiseFloor * 2.8).toInt(),
                    700,
                )

                if (amplitude >= adaptiveThreshold) {
                    voiceHitCount += 1
                    quietHitCount = 0
                    if (voiceHitCount >= 2) speechStarted = true
                    lastVoiceAt = now
                } else {
                    if (speechStarted) {
                        quietHitCount += 1
                    } else {
                        voiceHitCount = 0
                    }
                }

                val noSpeechExpired = !speechStarted && elapsed >= noSpeechTimeoutMs
                val silenceExpired = speechStarted &&
                    elapsed >= minDurationMs &&
                    now - lastVoiceAt >= silenceAfterSpeechMs &&
                    quietHitCount >= 3
                val maxExpired = elapsed >= maxDurationMs

                if (noSpeechExpired || silenceExpired || maxExpired) break
            }

            recorder.stop()
            if (!speechStarted) {
                runCatching { output.delete() }
                return@withContext RecordingResult.NoSpeech
            }
        } catch (e: RuntimeException) {
            runCatching { output.delete() }
            throw e
        } finally {
            recorder.release()
        }

        RecordingResult.Audio(output)
    }

    sealed interface RecordingResult {
        data class Audio(val file: File) : RecordingResult
        data object NoSpeech : RecordingResult
    }
}
