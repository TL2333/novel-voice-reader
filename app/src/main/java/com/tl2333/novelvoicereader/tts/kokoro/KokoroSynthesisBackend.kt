package com.tl2333.novelvoicereader.tts.kokoro

data class KokoroGenerationRequest(
    val silenceScale: Float,
    val synthesisProfileSpeed: Float,
    val voiceSid: Int,
)

data class KokoroGeneratedAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val generationDurationMs: Long,
)

interface KokoroSynthesisBackend {
    val sampleRate: Int
    val speakerCount: Int
    fun generate(text: String, request: KokoroGenerationRequest): KokoroGeneratedAudio
    fun release()
}
