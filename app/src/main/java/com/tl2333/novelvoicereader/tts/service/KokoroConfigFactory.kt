package com.tl2333.novelvoicereader.tts.service

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import com.tl2333.novelvoicereader.tts.kokoro.KokoroAssetLayout
import java.io.File

internal object KokoroConfigFactory {
    const val DEFAULT_NUM_THREADS = 4

    private const val LEXICONS =
        "${KokoroAssetLayout.ASSET_DIRECTORY}/lexicon-us-en.txt,${KokoroAssetLayout.ASSET_DIRECTORY}/lexicon-zh.txt"
    private const val RULE_FSTS =
        "${KokoroAssetLayout.ASSET_DIRECTORY}/phone-zh.fst,${KokoroAssetLayout.ASSET_DIRECTORY}/date-zh.fst,${KokoroAssetLayout.ASSET_DIRECTORY}/number-zh.fst"

    fun create(runtimeEspeakDataDirectory: File, numThreads: Int = DEFAULT_NUM_THREADS): OfflineTtsConfig {
        require(runtimeEspeakDataDirectory.isDirectory)
        require(numThreads > 0)
        return getOfflineTtsConfig(
            modelDir = KokoroAssetLayout.ASSET_DIRECTORY,
            modelName = KokoroAssetLayout.MODEL_FILE,
            acousticModelName = "",
            vocoder = "",
            voices = KokoroAssetLayout.VOICES_FILE,
            lexicon = LEXICONS,
            dataDir = runtimeEspeakDataDirectory.absolutePath,
            dictDir = "",
            ruleFsts = RULE_FSTS,
            ruleFars = "",
            numThreads = numThreads,
        )
    }
}
