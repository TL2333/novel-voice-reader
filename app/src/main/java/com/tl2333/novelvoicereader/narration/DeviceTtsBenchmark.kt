package com.tl2333.novelvoicereader.narration

import android.content.Context
import com.tl2333.novelvoicereader.tts.client.TtsInferenceClient
import com.tl2333.novelvoicereader.tts.kokoro.KokoroGenerationRequest
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class DeviceTtsBenchmarkResult(
    val statistics: RtfStatistics,
    val loadFactors: Map<Float, Double>,
    val measuredAt: Long,
)

object DeviceTtsBenchmark {
    private const val FILE_NAME = "device-tts-benchmark.json"

    suspend fun run(context: Context, voiceSid: Int): DeviceTtsBenchmarkResult = withContext(Dispatchers.IO) {
        val tracker = RtfTracker(BENCHMARK_TEXTS.size)
        val client = TtsInferenceClient.connect(context.applicationContext)
        try {
            BENCHMARK_TEXTS.forEach { text ->
                val generated = client.generate(text, KokoroGenerationRequest(0.2f, 1f, voiceSid))
                val audioMs = generated.samples.size * 1_000L / generated.sampleRate
                tracker.record(generated.generationDurationMs, audioMs)
            }
        } finally {
            client.release()
        }
        val stats = requireNotNull(tracker.statistics())
        DeviceTtsBenchmarkResult(
            stats,
            SUPPORTED_SPEEDS.associateWith { speed -> stats.p95 * speed },
            System.currentTimeMillis(),
        ).also { write(context, it) }
    }

    fun load(context: Context): DeviceTtsBenchmarkResult? = runCatching {
        val value = Json.parseToJsonElement(File(context.filesDir, FILE_NAME).readText()).jsonObject
        val stats = RtfStatistics(
            p50 = value.getValue("p50").jsonPrimitive.content.toDouble(),
            p90 = value.getValue("p90").jsonPrimitive.content.toDouble(),
            p95 = value.getValue("p95").jsonPrimitive.content.toDouble(),
            sampleCount = value.getValue("sampleCount").jsonPrimitive.content.toInt(),
        )
        DeviceTtsBenchmarkResult(
            stats,
            SUPPORTED_SPEEDS.associateWith { stats.p95 * it },
            value.getValue("measuredAt").jsonPrimitive.content.toLong(),
        )
    }.getOrNull()

    private fun write(context: Context, result: DeviceTtsBenchmarkResult) {
        val file = File(context.filesDir, FILE_NAME)
        val json = buildJsonObject {
            put("measuredAt", result.measuredAt)
            put("p50", result.statistics.p50)
            put("p90", result.statistics.p90)
            put("p95", result.statistics.p95)
            put("sampleCount", result.statistics.sampleCount)
            result.loadFactors.forEach { (speed, factor) -> put("loadFactor${speed}x", factor) }
        }
        val temporary = File(file.parentFile, ".${file.name}.part")
        temporary.writeText(json.toString(), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private val SUPPORTED_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
    private val BENCHMARK_TEXTS = listOf(
        "夜色渐渐沉了下来，远处的灯火在雨幕中忽明忽暗。",
        "清晨的第一班列车穿过山谷，惊起了栖息在林间的鸟群。",
        "她放下手中的书，轻声问道，你已经准备好出发了吗？",
        "风从半开的窗户吹进来，带着潮湿泥土和青草的气息。",
        "会议安排在二零二六年八月十四日上午九点三十分。",
        "这件商品原价一千二百九十九元五角，现在优惠百分之十二点五。",
        "如果实时生成速度不足，播放器会等待缓冲恢复，而不会跳过句子。",
        "所有音频都在设备本地生成，不会上传正文，也不依赖云端服务。",
        "第一章，风暴之前。",
        "你听见了吗？那是雨点落在屋檐上的声音。",
        "停下！前面的道路已经被洪水淹没。",
        "他沉默了很久……最后只说了一句，我们继续。",
        "The reader supports offline English and Chinese narration on this device.",
        "Media3 负责 playback speed，Kokoro 始终生成标准速度音频。",
        "短句结束。",
        "这是一段稍长的测试文字，用来观察模型在连续中文标点、自然停顿和多个语义分句之间的合成耗时是否保持稳定。",
        "缓存命中时不需要再次合成，因此基准测试会绕开朗读缓存并直接测量推理。",
        "缓冲区按用户实际听到的时间计算，也就是剩余媒体时长除以当前播放倍速。",
        "当二倍速负载因子接近一时，系统会提前准备更长的后续内容。",
        "最后一个测试段落用于完成二十个固定样本的离线性能统计。",
    )
}
