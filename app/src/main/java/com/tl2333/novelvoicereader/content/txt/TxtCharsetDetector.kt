package com.tl2333.novelvoicereader.content.txt

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DetectedCharset(val charset: Charset, val bomBytes: Int, val confidence: Double)

object TxtCharsetDetector {
    private const val SAMPLE_BYTES = 64 * 1024
    private val utf8 = Charsets.UTF_8
    private val candidates = listOf("GB18030", "GBK", "Big5").map(Charset::forName)

    fun detect(input: InputStream): DetectedCharset {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(SAMPLE_BYTES + 4)
        val sampleBuffer = ByteArray(SAMPLE_BYTES)
        var sampleLength = 0
        while (sampleLength < sampleBuffer.size) {
            val count = buffered.read(sampleBuffer, sampleLength, sampleBuffer.size - sampleLength)
            if (count < 0) break
            sampleLength += count
        }
        val sample = sampleBuffer.copyOf(sampleLength)
        buffered.reset()
        if (sample.startsWith(0xEF, 0xBB, 0xBF)) return DetectedCharset(utf8, 3, 1.0)
        if (sample.startsWith(0xFF, 0xFE)) return DetectedCharset(Charsets.UTF_16LE, 2, 1.0)
        if (sample.startsWith(0xFE, 0xFF)) return DetectedCharset(Charsets.UTF_16BE, 2, 1.0)
        if (decodeStrict(sample, utf8) != null) return DetectedCharset(utf8, 0, 0.99)
        val scored = candidates.map { charset -> charset to score(decodeLenient(sample, charset)) }
            .maxByOrNull { it.second }
            ?: return DetectedCharset(utf8, 0, 0.1)
        return DetectedCharset(scored.first, 0, scored.second.coerceIn(0.1, 0.95))
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeLenient(bytes: ByteArray, charset: Charset): String = charset.decode(ByteBuffer.wrap(bytes)).toString()

    private fun score(text: String): Double {
        if (text.isEmpty()) return 0.5
        val cjk = text.count { it.code in 0x3400..0x9FFF }
        val printable = text.count { !it.isISOControl() || it in "\r\n\t" }
        val replacement = text.count { it == '\uFFFD' }
        return (printable + cjk * 1.5 - replacement * 10.0) / text.length.coerceAtLeast(1)
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it].toInt() and 0xff == values[it] }
}
