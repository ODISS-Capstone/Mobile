package com.odiss.assistant.audio

import android.content.Context
import com.odiss.assistant.data.OdissRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SttController(
    private val context: Context,
    private val repository: OdissRepository = OdissRepository(),
) {
    fun listenOnce(): Flow<String> = flow {
        val recording = VoiceRecorder.recordUntilSilence(
            context = context,
            prefix = "odiss-stt-",
        )
        val file = when (recording) {
            is VoiceRecorder.RecordingResult.Audio -> recording.file
            VoiceRecorder.RecordingResult.NoSpeech -> {
                emit("")
                return@flow
            }
        }
        val text = runCatching { repository.transcribeAudio(file) }
            .getOrElse { "" }
        runCatching { file.delete() }
        emit(text)
    }
}
