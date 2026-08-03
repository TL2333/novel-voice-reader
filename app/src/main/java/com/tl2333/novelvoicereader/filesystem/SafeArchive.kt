package com.tl2333.novelvoicereader.filesystem

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxSingleFileBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Double = 250.0,
    val maxPathLength: Int = 1_024,
    val maxPathDepth: Int = 64,
) {
    init {
        require(maxEntries > 0)
        require(maxSingleFileBytes >= 0 && maxTotalBytes >= 0)
        require(maxCompressionRatio >= 1.0 && maxCompressionRatio.isFinite())
        require(maxPathLength > 0 && maxPathDepth > 0)
    }
}

enum class ArchiveViolation {
    NOT_FOUND,
    INVALID_ARCHIVE,
    INVALID_PATH,
    PATH_TRAVERSAL,
    DUPLICATE_PATH,
    ENTRY_TYPE_CONFLICT,
    TOO_MANY_ENTRIES,
    UNKNOWN_SIZE,
    ENTRY_TOO_LARGE,
    TOTAL_TOO_LARGE,
    COMPRESSION_RATIO,
    SIZE_MISMATCH,
    CRC_MISMATCH,
    DESTINATION_EXISTS,
    EXTRACTION_FAILED,
}

class UnsafeArchiveException(
    val violation: ArchiveViolation,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class InspectedArchiveEntry(
    val archiveName: String,
    val normalizedPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val crc: Long,
)

data class ArchiveInspection(
    val entries: List<InspectedArchiveEntry>,
    val totalBytes: Long,
) {
    operator fun get(path: String): InspectedArchiveEntry? =
        entries.firstOrNull { it.normalizedPath == SafeArchive.normalizeEntryPath(path) }
}

object SafeArchive {
    private val drivePrefix = Regex("^[A-Za-z]:")
    private val reservedNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (index in 1..9) {
            add("COM$index")
            add("LPT$index")
        }
    }

    fun normalizeEntryPath(
        rawName: String,
        maxPathLength: Int = 1_024,
        maxPathDepth: Int = 64,
    ): String {
        if (rawName.isBlank() || rawName.startsWith('/') || rawName.startsWith('\\') ||
            drivePrefix.containsMatchIn(rawName)
        ) {
            throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Invalid archive path: $rawName")
        }
        if (rawName.any { it == '\u0000' || it.code in 1..31 || it.code == 127 }) {
            throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Control character in archive path")
        }
        val slashPath = rawName.replace('\\', '/').removeSuffix("/")
        val segments = slashPath.split('/')
        if (slashPath.isEmpty() || segments.size > maxPathDepth) {
            throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Invalid archive path: $rawName")
        }
        val normalized = segments.map { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw UnsafeArchiveException(ArchiveViolation.PATH_TRAVERSAL, "Unsafe archive path: $rawName")
            }
            if (':' in segment || segment.endsWith('.') || segment.endsWith(' ')) {
                throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Ambiguous archive path: $rawName")
            }
            Normalizer.normalize(segment, Normalizer.Form.NFC).also {
                if (it.substringBefore('.').uppercase(Locale.ROOT) in reservedNames) {
                    throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Reserved archive path: $rawName")
                }
            }
        }.joinToString("/")
        if (normalized.length > maxPathLength) {
            throw UnsafeArchiveException(ArchiveViolation.INVALID_PATH, "Archive path is too long")
        }
        return normalized
    }

    fun inspect(file: File, limits: ArchiveLimits = ArchiveLimits()): ArchiveInspection {
        if (!file.isFile) throw UnsafeArchiveException(ArchiveViolation.NOT_FOUND, "Archive not found: $file")
        return openZip(file) { zip ->
            val entries = mutableListOf<InspectedArchiveEntry>()
            val knownPaths = mutableMapOf<String, Boolean>()
            var total = 0L
            val sourceEntries = zip.entries()
            while (sourceEntries.hasMoreElements()) {
                if (entries.size >= limits.maxEntries) {
                    throw UnsafeArchiveException(ArchiveViolation.TOO_MANY_ENTRIES, "Too many archive entries")
                }
                val entry = sourceEntries.nextElement()
                val path = normalizeEntryPath(entry.name, limits.maxPathLength, limits.maxPathDepth)
                if (entry.size < 0 || entry.compressedSize < 0) {
                    throw UnsafeArchiveException(ArchiveViolation.UNKNOWN_SIZE, "Unknown entry size: ${entry.name}")
                }
                if (!entry.isDirectory && entry.size > limits.maxSingleFileBytes) {
                    throw UnsafeArchiveException(ArchiveViolation.ENTRY_TOO_LARGE, "Entry is too large: ${entry.name}")
                }
                total = checkedAdd(total, entry.size, limits.maxTotalBytes)
                checkRatio(entry.size, entry.compressedSize, limits.maxCompressionRatio, entry.name)
                registerPath(knownPaths, path, entry.isDirectory)
                entries += InspectedArchiveEntry(
                    entry.name,
                    path,
                    entry.isDirectory,
                    entry.size,
                    entry.compressedSize,
                    entry.crc,
                )
            }
            ArchiveInspection(entries, total)
        }
    }

    fun extractAtomically(
        file: File,
        destination: File,
        limits: ArchiveLimits = ArchiveLimits(),
    ): ArchiveInspection {
        if (destination.exists()) {
            throw UnsafeArchiveException(ArchiveViolation.DESTINATION_EXISTS, "Destination already exists")
        }
        val inspection = inspect(file, limits)
        val parent = destination.absoluteFile.parentFile
            ?: throw UnsafeArchiveException(ArchiveViolation.EXTRACTION_FAILED, "Destination has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw UnsafeArchiveException(ArchiveViolation.EXTRACTION_FAILED, "Cannot create destination parent")
        }
        val staging = File(parent, ".${destination.name}.extracting-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw UnsafeArchiveException(ArchiveViolation.EXTRACTION_FAILED, "Cannot stage extraction")
        try {
            openZip(file) { zip ->
                for (safeEntry in inspection.entries) {
                    val source = zip.getEntry(safeEntry.archiveName)
                        ?: throw UnsafeArchiveException(ArchiveViolation.SIZE_MISMATCH, "Entry disappeared")
                    val target = resolveInside(staging, safeEntry.normalizedPath)
                    if (safeEntry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create directory")
                        continue
                    }
                    target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent") }
                    copyVerified(zip, source, target, safeEntry, limits)
                }
            }
            if (!staging.renameTo(destination.absoluteFile)) {
                throw UnsafeArchiveException(ArchiveViolation.EXTRACTION_FAILED, "Cannot commit extraction")
            }
            return inspection
        } catch (error: UnsafeArchiveException) {
            throw error
        } catch (error: Exception) {
            throw UnsafeArchiveException(ArchiveViolation.EXTRACTION_FAILED, "Archive extraction failed", error)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun readEntryBytes(file: File, path: String, maxBytes: Long): ByteArray = openZip(file) { zip ->
        val normalized = normalizeEntryPath(path)
        val entry = zip.entries().asSequence().firstOrNull {
            runCatching { normalizeEntryPath(it.name) }.getOrNull() == normalized
        } ?: throw UnsafeArchiveException(ArchiveViolation.NOT_FOUND, "Entry not found: $path")
        if (entry.isDirectory || entry.size > maxBytes) {
            throw UnsafeArchiveException(ArchiveViolation.ENTRY_TOO_LARGE, "Entry cannot be read: $path")
        }
        zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
            val buffer = ByteArray(16 * 1024)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count = checkedAdd(count, read.toLong(), maxBytes)
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun resolveInside(root: File, path: String): File {
        val base = root.canonicalFile
        val target = File(base, path.replace('/', File.separatorChar)).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator, ignoreCase = File.separatorChar == '\\')) {
            throw UnsafeArchiveException(ArchiveViolation.PATH_TRAVERSAL, "Path escapes extraction root")
        }
        return target
    }

    private fun registerPath(paths: MutableMap<String, Boolean>, path: String, directory: Boolean) {
        val key = path.lowercase(Locale.ROOT)
        val old = paths[key]
        if (old != null) {
            val violation = if (old == directory) ArchiveViolation.DUPLICATE_PATH else ArchiveViolation.ENTRY_TYPE_CONFLICT
            throw UnsafeArchiveException(violation, "Duplicate archive path: $path")
        }
        val segments = key.split('/')
        for (index in 1 until segments.size) {
            val parent = segments.take(index).joinToString("/")
            if (paths[parent] == false) {
                throw UnsafeArchiveException(ArchiveViolation.ENTRY_TYPE_CONFLICT, "File is used as directory: $path")
            }
            paths.putIfAbsent(parent, true)
        }
        if (!directory && paths.keys.any { it.startsWith("$key/") }) {
            throw UnsafeArchiveException(ArchiveViolation.ENTRY_TYPE_CONFLICT, "Directory is used as file: $path")
        }
        paths[key] = directory
    }

    private fun checkedAdd(current: Long, added: Long, maximum: Long): Long {
        val total = try {
            Math.addExact(current, added)
        } catch (_: ArithmeticException) {
            throw UnsafeArchiveException(ArchiveViolation.TOTAL_TOO_LARGE, "Archive size overflow")
        }
        if (total > maximum) throw UnsafeArchiveException(ArchiveViolation.TOTAL_TOO_LARGE, "Archive size limit exceeded")
        return total
    }

    private fun checkRatio(size: Long, compressed: Long, maxRatio: Double, name: String) {
        if (size > 0 && (compressed == 0L || size.toDouble() / compressed > maxRatio)) {
            throw UnsafeArchiveException(ArchiveViolation.COMPRESSION_RATIO, "Compression ratio too high: $name")
        }
    }

    private fun copyVerified(
        zip: ZipFile,
        source: ZipEntry,
        target: File,
        expected: InspectedArchiveEntry,
        limits: ArchiveLimits,
    ) {
        val crc = CRC32()
        var count = 0L
        zip.getInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count = checkedAdd(count, read.toLong(), limits.maxSingleFileBytes)
                    crc.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (count != expected.sizeBytes) throw UnsafeArchiveException(ArchiveViolation.SIZE_MISMATCH, "Entry size mismatch")
        if (expected.crc >= 0 && crc.value != expected.crc) {
            throw UnsafeArchiveException(ArchiveViolation.CRC_MISMATCH, "Entry CRC mismatch")
        }
    }

    private inline fun <T> openZip(file: File, block: (ZipFile) -> T): T = try {
        ZipFile(file).use(block)
    } catch (error: UnsafeArchiveException) {
        throw error
    } catch (error: ZipException) {
        throw UnsafeArchiveException(ArchiveViolation.INVALID_ARCHIVE, "Invalid ZIP archive", error)
    } catch (error: IOException) {
        throw UnsafeArchiveException(ArchiveViolation.INVALID_ARCHIVE, "Cannot read ZIP archive", error)
    }
}
