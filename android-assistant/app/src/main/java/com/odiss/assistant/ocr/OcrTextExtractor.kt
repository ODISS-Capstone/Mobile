package com.odiss.assistant.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrTextExtractor(context: Context) {
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    suspend fun extract(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                continuation.resume(result.text.orEmpty())
            }
            .addOnFailureListener {
                continuation.resume("")
            }
    }
}
