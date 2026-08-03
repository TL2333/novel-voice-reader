package com.tl2333.novelvoicereader.tts.cache

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

data class WavInfo(
    val file: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val durationMillis: Long,
    val sizeBytes: Long,
)

object WavWriter {
    fun floatToPcm16(samples: FloatArray, gain: Float = 1f): ShortArray {
        require(gain.isFinite() && gain >= 0f)
        return ShortArray(samples.size) { index ->
            val sample = samples[index].takeIf(Float::isFinite)?.times(gain)?.coerceIn(-1f, 1f) ?: 0f
            val scaled = if (sample < 0f) sample * 32768f else sample * 32767f
            scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun writeMonoPcm16(
        destination: File,
        samples: FloatArray,
        sampleRate: Int,
        gain: Float = 1f,
    ): WavInfo = writeMonoPcm16(destination, floatToPcm16(samples, gain), sampleRate)

    fun writeMonoPcm16(destination: File, samples: ShortArray, sampleRate: Int): WavInfo {
        require(sampleRate in 8_000..192_000)
        val dataBytes = Math.multiplyExact(samples.size, 2)
        val riffSize = Math.addExact(36, dataBytes)
        destination.absoluteFile.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) throw IOException("Could not create WAV parent directory")
        }
        val temporary = File(destination.absoluteFile.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput, 64 * 1024)
                output.write("RIFF".toByteArray(Charsets.US_ASCII))
                output.writeLittleEndian(riffSize)
                output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                output.writeLittleEndian(16)
                output.writeLittleEndianShort(1)
                output.writeLittleEndianShort(1)
                output.writeLittleEndian(sampleRate)
                output.writeLittleEndian(sampleRate * 2)
                output.writeLittleEndianShort(2)
                output.writeLittleEndianShort(16)
                output.write("data".toByteArray(Charsets.US_ASCII))
                output.writeLittleEndian(dataBytes)
                for (sample in samples) output.writeLittleEndianShort(sample.toInt())
                output.flush()
                fileOutput.fd.sync()
            }
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace WAV destination")
            if (!temporary.renameTo(destination)) throw IOException("Could not commit WAV file")
            return WavInfo(
                destination,
                sampleRate,
                samples.size,
                samples.size * 1_000L / sampleRate,
                destination.length(),
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

private fun java.io.OutputStream.writeLittleEndian(value: Int) {
    write(value and 0xff)
    write(value ushr 8 and 0xff)
    write(value ushr 16 and 0xff)
    write(value ushr 24 and 0xff)
}

private fun java.io.OutputStream.writeLittleEndianShort(value: Int) {
    write(value and 0xff)
    write(value ushr 8 and 0xff)
}
