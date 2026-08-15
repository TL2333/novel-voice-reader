package com.tl2333.novelvoicereader.tts.kokoro

import com.tl2333.novelvoicereader.filesystem.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.Locale

data class KokoroModelFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class KokoroModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val modelVersion: String,
    val huggingFaceCommit: String,
    val sourceUrl: String,
    val archiveSha256: String,
    val generatedAtUtc: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val files: List<KokoroModelFile>,
) {
    init {
        require(schemaVersion > 0)
        require(modelId.isNotBlank() && modelVersion.isNotBlank())
        require(COMMIT.matches(huggingFaceCommit))
        require(sourceUrl.startsWith("https://"))
        require(SHA256.matches(archiveSha256))
        require(generatedAtUtc.isNotBlank())
        require(fileCount == files.size)
        require(totalSizeBytes == files.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) })
        require(files.map { it.path.lowercase(Locale.ROOT) }.distinct().size == files.size) {
            "Manifest contains duplicate paths"
        }
        files.forEach(::validateFile)
    }

    fun file(path: String): KokoroModelFile? = files.firstOrNull { it.path == path }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val COMMIT = Regex("^[0-9a-f]{40}$")

        fun parse(source: String): KokoroModelManifest {
            val root = json.parseToJsonElement(source).jsonObject
            val files = root.requiredArray("files").map { item ->
                val value = item.jsonObject
                KokoroModelFile(
                    path = value.requiredString("path"),
                    sizeBytes = value.requiredLong("sizeBytes"),
                    sha256 = value.requiredString("sha256").lowercase(Locale.ROOT),
                )
            }
            return KokoroModelManifest(
                schemaVersion = root.requiredLong("schemaVersion").toIntExact("schemaVersion"),
                modelId = root.requiredString("modelId"),
                modelVersion = root.requiredString("modelVersion"),
                huggingFaceCommit = root.requiredString("huggingFaceCommit").lowercase(Locale.ROOT),
                sourceUrl = root.requiredString("sourceUrl"),
                archiveSha256 = root.requiredString("archiveSha256").lowercase(Locale.ROOT),
                generatedAtUtc = root.requiredString("generatedAtUtc"),
                fileCount = root.requiredLong("fileCount").toIntExact("fileCount"),
                totalSizeBytes = root.requiredLong("totalSizeBytes"),
                files = files,
            )
        }

        private fun validateFile(file: KokoroModelFile) {
            require(file.path.isNotBlank())
            require(!File(file.path).isAbsolute && !file.path.startsWith('/') && !file.path.startsWith('\\'))
            require(!Regex("^[A-Za-z]:").containsMatchIn(file.path))
            val segments = file.path.replace('\\', '/').split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe model path: ${file.path}" }
            require(file.sizeBytes >= 0)
            require(SHA256.matches(file.sha256)) { "Invalid SHA-256 for ${file.path}" }
        }
    }
}

data class KokoroModelVerification(
    val isValid: Boolean,
    val missingPaths: List<String>,
    val corruptPaths: List<String>,
    val verifiedBytes: Long,
)

object KokoroModelVerifier {
    const val PINNED_COMMIT = "155831f1b4ba23b1f5c058be6a61df90cefb2a37"
    const val MODEL_SHA256 = "bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26"
    const val MODEL_SIZE_BYTES = 114_299_010L
    const val LEXICON_ZH_SHA256 = "11111d8cd695fba2ace1367a1d0a708b586e6ef5c1f9be91da5d7eef129b651c"

    val REQUIRED_PATHS = setOf(
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
        "lexicon-zh.txt",
        "lexicon-us-en.txt",
        "lexicon-gb-en.txt",
        "phone-zh.fst",
        "date-zh.fst",
        "number-zh.fst",
        "dict",
        "espeak-ng-data",
        "LICENSE",
        "README.md",
    )

    fun verify(
        root: File,
        manifest: KokoroModelManifest,
        requiredPaths: Set<String> = REQUIRED_PATHS,
        enforcePinnedCoreHashes: Boolean = true,
    ): KokoroModelVerification {
        val missing = linkedSetOf<String>()
        val corrupt = linkedSetOf<String>()
        if (!root.isDirectory) missing += root.path
        for (path in requiredPaths) {
            if (!File(root, path).exists()) missing += path
        }
        if (enforcePinnedCoreHashes) {
            if (manifest.huggingFaceCommit != PINNED_COMMIT) corrupt += "huggingFaceCommit"
            val model = manifest.file("model.int8.onnx")
            if (model?.sizeBytes != MODEL_SIZE_BYTES || model?.sha256 != MODEL_SHA256) corrupt += "model.int8.onnx"
            if (manifest.file("lexicon-zh.txt")?.sha256 != LEXICON_ZH_SHA256) corrupt += "lexicon-zh.txt"
        }

        var verifiedBytes = 0L
        for (entry in manifest.files) {
            val file = resolveInside(root, entry.path)
            if (file == null) {
                corrupt += entry.path
                continue
            }
            if (!file.isFile) {
                missing += entry.path
                continue
            }
            if (file.length() != entry.sizeBytes || Sha256.hash(file) != entry.sha256) {
                corrupt += entry.path
                continue
            }
            verifiedBytes = Math.addExact(verifiedBytes, entry.sizeBytes)
        }
        if (verifiedBytes != manifest.totalSizeBytes && missing.isEmpty() && corrupt.isEmpty()) {
            corrupt += "totalSizeBytes"
        }
        if (File(root, "voices.bin").isFile && File(root, "voices.bin").length() == 0L) corrupt += "voices.bin"
        return KokoroModelVerification(missing.isEmpty() && corrupt.isEmpty(), missing.toList(), corrupt.toList(), verifiedBytes)
    }

    private fun resolveInside(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(root, relativePath.replace('/', File.separatorChar)).canonicalFile }.getOrNull()
            ?: return null
        return candidate.takeIf {
            it == canonicalRoot || it.path.startsWith(canonicalRoot.path + File.separator, ignoreCase = File.separatorChar == '\\')
        }
    }
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing string: $name")

private fun JsonObject.requiredLong(name: String): Long =
    runCatching { get(name)?.jsonPrimitive?.long }.getOrNull()
        ?: throw IllegalArgumentException("Missing integer: $name")

private fun JsonObject.requiredArray(name: String): JsonArray =
    runCatching { get(name)?.jsonArray }.getOrNull()
        ?: throw IllegalArgumentException("Missing array: $name")

private fun Long.toIntExact(name: String): Int =
    takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw IllegalArgumentException("$name is outside Int range")
