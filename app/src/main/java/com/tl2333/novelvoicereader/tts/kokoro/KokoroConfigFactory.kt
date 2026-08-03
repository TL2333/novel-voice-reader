package com.tl2333.novelvoicereader.tts.kokoro

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File

object KokoroConfigFactory {
    const val ASSET_DIRECTORY = "kokoro"
    const val MODEL_FILE = "model.int8.onnx"
    const val VOICES_FILE = "voices.bin"
    const val DEFAULT_NUM_THREADS = 4

    private const val LEXICONS =
        "$ASSET_DIRECTORY/lexicon-us-en.txt,$ASSET_DIRECTORY/lexicon-zh.txt"
    private const val RULE_FSTS =
        "$ASSET_DIRECTORY/phone-zh.fst,$ASSET_DIRECTORY/date-zh.fst,$ASSET_DIRECTORY/number-zh.fst"

    fun create(
        runtimeEspeakDataDirectory: File,
        numThreads: Int = DEFAULT_NUM_THREADS,
    ): OfflineTtsConfig {
        require(runtimeEspeakDataDirectory.isDirectory) {
            "Kokoro espeak-ng-data directory is missing: $runtimeEspeakDataDirectory"
        }
        require(numThreads > 0)

        return getOfflineTtsConfig(
            modelDir = ASSET_DIRECTORY,
            modelName = MODEL_FILE,
            acousticModelName = "",
            vocoder = "",
            voices = VOICES_FILE,
            lexicon = LEXICONS,
            dataDir = runtimeEspeakDataDirectory.absolutePath,
            dictDir = "",
            ruleFsts = RULE_FSTS,
            ruleFars = "",
            numThreads = numThreads,
        )
    }
}
