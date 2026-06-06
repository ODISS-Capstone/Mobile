package com.odiss.assistant.ocr

import com.odiss.assistant.model.MedicationInput

/** OCR 원문에서 약 항목 후보를 추출하는 간단한 휴리스틱(앱/촬영 공용). */
object MedicationParser {
    fun parse(raw: String): List<MedicationInput> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val meds = mutableListOf<MedicationInput>()
        for (line in lines) {
            if (line.length < 2) continue
            if (line.contains("mg") || line.contains("정") || line.contains("캡슐")) {
                meds += MedicationInput(name = line.take(80))
            }
        }
        if (meds.isEmpty() && raw.isNotBlank()) {
            meds += MedicationInput(name = raw.take(60))
        }
        return meds
    }
}
