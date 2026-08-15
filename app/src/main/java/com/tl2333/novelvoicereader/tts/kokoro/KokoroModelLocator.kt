package com.tl2333.novelvoicereader.tts.kokoro

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class KokoroModelFailure {
    MISSING,
    CORRUPT,
    STORAGE,
}

class KokoroModelPreparationException(
    val failure: KokoroModelFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class KokoroPreparationProgress(
    val phase: String,
    val currentFile: String?,
    val processedBytes: Long,
    val totalBytes: Long,
)

data class KokoroRuntimeModel(
    val manifest: KokoroModelManifest,
    val espeakDataDirectory: File,
)

data class KokoroAssetVerification(
    val isValid: Boolean,
    val missingPaths: List<String>,
    val corruptPaths: List<String>,
    val verifiedBytes: Long,
)

class KokoroModelLocator(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val assets: AssetManager = applicationContext.assets
    private val runtimeRoot = File(applicationContext.noBackupFilesDir, "kokoro-runtime")
    private val preparationMutex = Mutex()
    private var verifiedEngineAssetsCommit: String? = null

    suspend fun prepareRuntimeData(
        onProgress: (KokoroPreparationProgress) -> Unit = {},
    ): KokoroRuntimeModel = preparationMutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = loadManifest()
            enforcePinnedManifest(manifest)

            if (verifiedEngineAssetsCommit != manifest.huggingFaceCommit) {
                val engineAssetVerification = verifyPackagedAssets(
                    manifest,
                    ENGINE_ASSET_PATHS,
                    onProgress,
                )
                if (!engineAssetVerification.isValid) {
                    throw KokoroModelPreparationException(
                        if (engineAssetVerification.missingPaths.isNotEmpty()) {
                            KokoroModelFailure.MISSING
                        } else {
                            KokoroModelFailure.CORRUPT
                        },
                        "Packaged Kokoro engine assets failed verification. " +
                            "Missing=${engineAssetVerification.missingPaths}, " +
                            "corrupt=${engineAssetVerification.corruptPaths}",
                    )
                }
                verifiedEngineAssetsCommit = manifest.huggingFaceCommit
            }

        val dataEntries = manifest.files.filter { it.path.startsWith("$ESPEAK_DIRECTORY/") }
        if (dataEntries.isEmpty()) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.MISSING,
                "Kokoro manifest does not contain espeak-ng-data",
            )
        }

        runtimeRoot.mkdirs()
        val finalRoot = File(runtimeRoot, manifest.huggingFaceCommit)
        if (isInstalledDataValid(finalRoot, manifest, dataEntries)) {
            onProgress(KokoroPreparationProgress("ready", null, dataEntries.sumOf { it.sizeBytes }, dataEntries.sumOf { it.sizeBytes }))
            return@withContext KokoroRuntimeModel(manifest, File(finalRoot, ESPEAK_DIRECTORY))
        }

        if (finalRoot.exists() && !finalRoot.deleteRecursively()) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.STORAGE,
                "Unable to remove incomplete Kokoro runtime data: $finalRoot",
            )
        }

        val totalBytes = dataEntries.sumOf { it.sizeBytes }
        val minimumFreeBytes = totalBytes + totalBytes / 2
        if (runtimeRoot.usableSpace < minimumFreeBytes) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.STORAGE,
                "Insufficient storage for Kokoro runtime data. Required=$minimumFreeBytes, available=${runtimeRoot.usableSpace}",
            )
        }

        val temporaryRoot = File(runtimeRoot, ".${manifest.huggingFaceCommit}-${UUID.randomUUID()}.tmp")
        if (!temporaryRoot.mkdirs()) {
            throw KokoroModelPreparationException(KokoroModelFailure.STORAGE, "Unable to create $temporaryRoot")
        }

        try {
            var processedBytes = 0L
            for (entry in dataEntries) {
                onProgress(KokoroPreparationProgress("copying", entry.path, processedBytes, totalBytes))
                val relative = entry.path.removePrefix("$ESPEAK_DIRECTORY/")
                val destination = resolveInside(File(temporaryRoot, ESPEAK_DIRECTORY), relative)
                    ?: throw KokoroModelPreparationException(KokoroModelFailure.CORRUPT, "Unsafe manifest path: ${entry.path}")
                destination.parentFile?.mkdirs()
                copyVerifiedAsset(entry, destination)
                processedBytes += entry.sizeBytes
            }

            File(temporaryRoot, COMPLETE_MARKER).writeText(
                manifest.huggingFaceCommit,
                Charsets.UTF_8,
            )
            if (!isInstalledDataValid(temporaryRoot, manifest, dataEntries)) {
                throw KokoroModelPreparationException(
                    KokoroModelFailure.CORRUPT,
                    "Copied Kokoro runtime data failed verification",
                )
            }
            if (!temporaryRoot.renameTo(finalRoot)) {
                throw KokoroModelPreparationException(
                    KokoroModelFailure.STORAGE,
                    "Unable to atomically install Kokoro runtime data",
                )
            }
            onProgress(KokoroPreparationProgress("ready", null, totalBytes, totalBytes))
            KokoroRuntimeModel(manifest, File(finalRoot, ESPEAK_DIRECTORY))
        } catch (error: Throwable) {
            temporaryRoot.deleteRecursively()
            throw error
        }
        }
    }

    /** Removes the current versioned runtime copy and invalidates packaged-asset verification. */
    suspend fun resetRuntimeData() = preparationMutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = loadManifest()
            enforcePinnedManifest(manifest)
            verifiedEngineAssetsCommit = null

            val versionName = manifest.huggingFaceCommit
            val versionedEntries = runtimeRoot.listFiles().orEmpty().filter { entry ->
                entry.name == versionName || entry.name.startsWith(".$versionName-")
            }
            for (entry in versionedEntries) {
                if (entry.exists() && !entry.deleteRecursively()) {
                    throw KokoroModelPreparationException(
                        KokoroModelFailure.STORAGE,
                        "Unable to remove Kokoro runtime data: $entry",
                    )
                }
            }
        }
    }

    suspend fun verifyAllPackagedAssets(
        onProgress: (KokoroPreparationProgress) -> Unit = {},
    ): KokoroAssetVerification = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        verifyPackagedAssets(manifest, manifest.files.mapTo(linkedSetOf()) { it.path }, onProgress)
    }

    fun loadManifest(): KokoroModelManifest {
        val source = try {
            assets.open("${KokoroAssetLayout.ASSET_DIRECTORY}/$MANIFEST_FILE").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Throwable) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.MISSING,
                "Packaged Kokoro manifest is missing",
                error,
            )
        }
        return try {
            KokoroModelManifest.parse(source)
        } catch (error: Throwable) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.CORRUPT,
                "Packaged Kokoro manifest is invalid",
                error,
            )
        }
    }

    private fun enforcePinnedManifest(manifest: KokoroModelManifest) {
        if (manifest.huggingFaceCommit != KokoroModelVerifier.PINNED_COMMIT ||
            manifest.file(KokoroAssetLayout.MODEL_FILE)?.sha256 != KokoroModelVerifier.MODEL_SHA256 ||
            manifest.file("lexicon-zh.txt")?.sha256 != KokoroModelVerifier.LEXICON_ZH_SHA256
        ) {
            throw KokoroModelPreparationException(
                KokoroModelFailure.CORRUPT,
                "Packaged Kokoro manifest does not match the dependency lock",
            )
        }
    }

    private fun verifyPackagedAssets(
        manifest: KokoroModelManifest,
        paths: Set<String>,
        onProgress: (KokoroPreparationProgress) -> Unit,
    ): KokoroAssetVerification {
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        var verifiedBytes = 0L
        val entries = paths.mapNotNull { path ->
            manifest.file(path) ?: run {
                missing += path
                null
            }
        }
        val totalBytes = entries.sumOf { it.sizeBytes }
        for (entry in entries) {
            onProgress(KokoroPreparationProgress("verifying", entry.path, verifiedBytes, totalBytes))
            try {
                val actual = digestAsset(entry.path)
                if (actual.first != entry.sizeBytes || actual.second != entry.sha256) {
                    corrupt += entry.path
                } else {
                    verifiedBytes += entry.sizeBytes
                }
            } catch (_: java.io.FileNotFoundException) {
                missing += entry.path
            } catch (_: Throwable) {
                corrupt += entry.path
            }
        }
        return KokoroAssetVerification(missing.isEmpty() && corrupt.isEmpty(), missing, corrupt, verifiedBytes)
    }

    private fun digestAsset(relativePath: String): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        assets.open("${KokoroAssetLayout.ASSET_DIRECTORY}/$relativePath", AssetManager.ACCESS_STREAMING).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return size to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copyVerifiedAsset(entry: KokoroModelFile, destination: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        assets.open("${KokoroAssetLayout.ASSET_DIRECTORY}/${entry.path}", AssetManager.ACCESS_STREAMING).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
                output.fd.sync()
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (size != entry.sizeBytes || hash != entry.sha256) {
            destination.delete()
            throw KokoroModelPreparationException(
                KokoroModelFailure.CORRUPT,
                "Verification failed while copying ${entry.path}",
            )
        }
    }

    private fun isInstalledDataValid(
        root: File,
        manifest: KokoroModelManifest,
        entries: List<KokoroModelFile>,
    ): Boolean {
        val installedCommit = runCatching {
            File(root, COMPLETE_MARKER).takeIf(File::isFile)?.readText(Charsets.UTF_8)
        }.getOrNull()
        if (installedCommit != manifest.huggingFaceCommit) {
            return false
        }
        val dataRoot = File(root, ESPEAK_DIRECTORY)
        return entries.all { entry ->
            val relative = entry.path.removePrefix("$ESPEAK_DIRECTORY/")
            val file = resolveInside(dataRoot, relative) ?: return@all false
            file.isFile && file.length() == entry.sizeBytes && sha256(file) == entry.sha256
        }
    }

    private fun resolveInside(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(root, relativePath).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path.startsWith(canonicalRoot.path + File.separator, ignoreCase = File.separatorChar == '\\')
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MANIFEST_FILE = "models-manifest.json"
        private const val ESPEAK_DIRECTORY = "espeak-ng-data"
        private const val COMPLETE_MARKER = ".complete"
        /** Every packaged file referenced by the service config, plus the required GB lexicon. */
        private val ENGINE_ASSET_PATHS = linkedSetOf(
            "model.int8.onnx",
            "voices.bin",
            "tokens.txt",
            "lexicon-zh.txt",
            "lexicon-us-en.txt",
            "lexicon-gb-en.txt",
            "phone-zh.fst",
            "date-zh.fst",
            "number-zh.fst",
        )
    }
}
