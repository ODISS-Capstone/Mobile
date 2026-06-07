package com.odiss.assistant.audio

import android.content.Context
import android.media.MediaRecorder
import com.odiss.assistant.data.OdissRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class SttController(
    private val context: Context,
    private val repository: OdissRepository = OdissRepository(),
) {
    fun listenOnce(durationMs: Long = DEFAULT_RECORDING_MS): Flow<String> = flow {
        val file = recordOnce(durationMs)
        val text = runCatching { repository.transcribeAudio(file) }
            .getOrElse { "" }
        runCatching { file.delete() }
        emit(text)
    }

    private suspend fun recordOnce(durationMs: Long): File = withContext(Dispatchers.IO) {
        val output = File.createTempFile("odiss-stt-", ".m4a", context.cacheDir)
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
            Thread.sleep(durationMs.coerceAtLeast(1_500L))
            recorder.stop()
        } finally {
            recorder.release()
        }
        output
    }

    companion object {
        private const val DEFAULT_RECORDING_MS = 4_500L
    }
}
