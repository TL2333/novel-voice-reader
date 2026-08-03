package com.tl2333.novelvoicereader.tts.cache

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class CachedPcm16Audio(
    val file: File,
    val samples: ShortArray,
    val sampleRate: Int,
)

data class CacheClearResult(
    val deletedFiles: List<File>,
    val retainedProtectedFiles: List<File>,
    val failedFiles: List<File>,
) {
    val successful: Boolean
        get() = failedFiles.isEmpty()
}

/** Filesystem-backed real Kokoro PCM cache with a global, protected-file-aware LRU. */
class TtsAudioCache(
    cacheRoot: File,
    bookId: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root = cacheRoot.absoluteFile
    private val bookDirectory = File(root, safeDirectoryName(bookId))

    init {
        require(maxBytes >= 0)
    }

    fun get(key: String): CachedPcm16Audio? {
        val file = fileForKey(key)
        if (!file.isFile) return null
        return try {
            val audio = readMonoPcm16Wav(file)
            file.setLastModified(clock())
            audio
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(
        key: String,
        samples: ShortArray,
        sampleRate: Int,
        protectedFile: File? = null,
    ): File {
        require(samples.isNotEmpty())
        val file = fileForKey(key)
        WavWriter.writeMonoPcm16(file, samples, sampleRate)
        file.setLastModified(clock())
        trim(protectedFiles = setOfNotNull(protectedFile?.absoluteFile, file.absoluteFile))
        return file
    }

    fun protect(file: File) {
        val path = file.absoluteFile.path
        synchronized(FILE_STATE_LOCK) {
            PROTECTED_PATH_COUNTS[path] = (PROTECTED_PATH_COUNTS[path] ?: 0) + 1
        }
    }

    fun unprotect(file: File) {
        val absoluteFile = file.absoluteFile
        val path = absoluteFile.path
        var deletedPendingFile = false
        synchronized(FILE_STATE_LOCK) {
            val remainingReferences = (PROTECTED_PATH_COUNTS[path] ?: 0) - 1
            if (remainingReferences > 0) {
                PROTECTED_PATH_COUNTS[path] = remainingReferences
            } else {
                PROTECTED_PATH_COUNTS.remove(path)
                if (PENDING_DELETE_PATHS.remove(path)) {
                    deletedPendingFile = !absoluteFile.exists() || absoluteFile.delete()
                }
            }
        }
        if (deletedPendingFile) deleteEmptyParents(absoluteFile.parentFile)
    }

    fun trim(protectedFiles: Set<File> = emptySet()): List<File> {
        if (!root.isDirectory) return emptyList()
        val protectedPaths = protectedFiles.mapTo(hashSetOf()) { it.absoluteFile.path }.apply {
            synchronized(FILE_STATE_LOCK) { addAll(PROTECTED_PATH_COUNTS.keys) }
        }
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .toList()
        var total = files.fold(0L) { size, file ->
            runCatching { Math.addExact(size, file.length()) }.getOrDefault(Long.MAX_VALUE)
        }
        if (total <= maxBytes) return emptyList()

        val deleted = mutableListOf<File>()
        for (
            file in files.sortedWith(
                compareBy<File>(
                    { candidate -> candidate.lastModified() },
                    { candidate -> candidate.path },
                ),
            )
        ) {
            if (file.absoluteFile.path in protectedPaths) continue
            val size = file.length()
            if (file.delete()) {
                deleted += file
                total = (total - size).coerceAtLeast(0L)
                deleteEmptyParents(file.parentFile)
            }
            if (total <= maxBytes) break
        }
        return deleted
    }

    private fun fileForKey(key: String): File {
        require(CACHE_KEY.matches(key)) { "Invalid TTS cache key" }
        val directory = bookDirectory
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create narration cache directory: $directory")
        }
        return File(directory, "$key.wav")
    }

    private fun deleteEmptyParents(start: File?) {
        var current = start
        while (current != null && current != root && current.isDirectory) {
            if (current.list()?.isNotEmpty() != false || !current.delete()) return
            current = current.parentFile
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024
        private val CACHE_KEY = Regex("^[0-9a-f]{64}$")
        private val SAFE_DIRECTORY = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val FILE_STATE_LOCK = Any()
        private val PROTECTED_PATH_COUNTS = hashMapOf<String, Int>()
        private val PENDING_DELETE_PATHS = hashSetOf<String>()

        private fun safeDirectoryName(bookId: String): String {
            val trimmed = bookId.trim()
            require(trimmed.isNotEmpty()) { "bookId must not be blank" }
            return if (SAFE_DIRECTORY.matches(trimmed) && trimmed != "." && trimmed != "..") trimmed else {
                com.tl2333.novelvoicereader.filesystem.Sha256.hash(trimmed)
            }
        }

        /** Clears one book while deferring deletion of WAV files that are currently playing. */
        fun clearBook(cacheRoot: File, bookId: String): CacheClearResult {
            val root = cacheRoot.absoluteFile
            return clearScope(root, File(root, safeDirectoryName(bookId)))
        }

        /** Clears every book while deferring deletion of WAV files that are currently playing. */
        fun clearAll(cacheRoot: File): CacheClearResult {
            val root = cacheRoot.absoluteFile
            return clearScope(root, root)
        }

        private fun clearScope(root: File, scope: File): CacheClearResult {
            if (!scope.exists()) return CacheClearResult(emptyList(), emptyList(), emptyList())

            val files = scope.walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.absoluteFile.path }
                .toList()
            val deleted = mutableListOf<File>()
            val retained = mutableListOf<File>()
            val failed = mutableListOf<File>()
            synchronized(FILE_STATE_LOCK) {
                for (file in files) {
                    val absoluteFile = file.absoluteFile
                    val path = absoluteFile.path
                    if ((PROTECTED_PATH_COUNTS[path] ?: 0) > 0) {
                        PENDING_DELETE_PATHS += path
                        retained += absoluteFile
                    } else if (!absoluteFile.exists() || absoluteFile.delete()) {
                        deleted += absoluteFile
                    } else {
                        failed += absoluteFile
                    }
                }
            }

            scope.walkBottomUp()
                .filter(File::isDirectory)
                .forEach { directory ->
                    if (directory != root || scope == root) directory.delete()
                }
            return CacheClearResult(deleted, retained, failed)
        }
    }
}

private fun readMonoPcm16Wav(file: File): CachedPcm16Audio {
    RandomAccessFile(file, "r").use { input ->
        if (input.length() < 44L || input.readAscii(4) != "RIFF") throw IOException("Invalid RIFF header")
        input.readLittleEndianInt() // RIFF payload size
        if (input.readAscii(4) != "WAVE") throw IOException("Invalid WAVE header")

        var format: WavFormat? = null
        var samples: ShortArray? = null
        while (input.filePointer + 8L <= input.length()) {
            val chunkId = input.readAscii(4)
            val chunkSize = input.readLittleEndianInt().toLong() and 0xffffffffL
            val chunkStart = input.filePointer
            val chunkEnd = chunkStart + chunkSize
            if (chunkEnd < chunkStart || chunkEnd > input.length()) throw IOException("Invalid WAV chunk size")
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L) throw IOException("Invalid WAV fmt chunk")
                    val audioFormat = input.readLittleEndianShort()
                    val channels = input.readLittleEndianShort()
                    val sampleRate = input.readLittleEndianInt()
                    input.readLittleEndianInt() // byte rate
                    input.readLittleEndianShort() // block alignment
                    val bitsPerSample = input.readLittleEndianShort()
                    if (audioFormat != 1 || channels != 1 || bitsPerSample != 16 || sampleRate !in 8_000..192_000) {
                        throw IOException("Cache WAV must be mono PCM16")
                    }
                    format = WavFormat(sampleRate)
                }

                "data" -> {
                    if (chunkSize % 2L != 0L || chunkSize / 2L > Int.MAX_VALUE) {
                        throw IOException("Invalid PCM16 data size")
                    }
                    val output = ShortArray((chunkSize / 2L).toInt())
                    for (index in output.indices) output[index] = input.readLittleEndianShort().toShort()
                    samples = output
                }
            }
            input.seek(chunkEnd + (chunkSize and 1L))
        }
        val actualFormat = format ?: throw IOException("Missing WAV fmt chunk")
        val actualSamples = samples?.takeIf { it.isNotEmpty() } ?: throw IOException("Missing WAV PCM data")
        return CachedPcm16Audio(file, actualSamples, actualFormat.sampleRate)
    }
}

private data class WavFormat(val sampleRate: Int)

private fun RandomAccessFile.readAscii(length: Int): String {
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}

private fun RandomAccessFile.readLittleEndianShort(): Int {
    val first = read()
    val second = read()
    if (first < 0 || second < 0) throw EOFException()
    return first or (second shl 8)
}

private fun RandomAccessFile.readLittleEndianInt(): Int {
    val first = readLittleEndianShort()
    val second = readLittleEndianShort()
    return first or (second shl 16)
}
