package com.tl2333.novelvoicereader.filesystem

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class Sha256Result(val hexDigest: String, val byteCount: Long)

object Sha256 {
    private const val BUFFER_SIZE = 64 * 1024

    fun digest(file: File): Sha256Result = file.inputStream().buffered().use(::digest)

    fun digest(input: InputStream): Sha256Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            count = Math.addExact(count, read.toLong())
        }
        return Sha256Result(digest.digest().toHex(), count)
    }

    fun hash(file: File): String = digest(file).hexDigest
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) append("%02x".format(byte.toInt() and 0xff))
}
