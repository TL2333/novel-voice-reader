package com.tl2333.novelvoicereader.content.model

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object CanonicalDocumentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(document: CanonicalDocument): String = buildJsonObject {
        put("id", document.id)
        put("sourceType", document.sourceType.name)
        put("title", document.title)
        document.author?.let { put("author", it) }
        put("sourceUri", document.sourceUri)
        put("contentHash", document.contentHash)
        put("metadata", stringMap(document.metadata))
        put("sections", buildJsonArray {
            document.sections.forEach { section ->
                add(buildJsonObject {
                    put("id", section.id)
                    section.title?.let { put("title", it) }
                    put("order", section.order)
                    section.anchor?.let { put("anchor", DocumentLocationJson.encode(it)) }
                })
            }
        })
        put("blocks", buildJsonArray {
            document.blocks.forEach { block ->
                add(buildJsonObject {
                    put("id", block.id)
                    put("sectionId", block.sectionId)
                    put("type", block.type.name)
                    put("text", block.text)
                    put("order", block.order)
                    put("anchor", DocumentLocationJson.encode(block.anchor))
                    put("metadata", stringMap(block.metadata))
                })
            }
        })
    }.toString()

    fun decode(value: String): CanonicalDocument {
        val root = json.parseToJsonElement(value).jsonObject
        return CanonicalDocument(
            id = root.required("id"),
            sourceType = SourceType.valueOf(root.required("sourceType")),
            title = root.required("title"),
            author = root.optional("author"),
            sourceUri = root.required("sourceUri"),
            contentHash = root.required("contentHash"),
            sections = root.getValue("sections").jsonArray.map { element ->
                val section = element.jsonObject
                DocumentSection(
                    id = section.required("id"),
                    title = section.optional("title"),
                    order = section.getValue("order").jsonPrimitive.int,
                    anchor = section.optional("anchor")?.let(DocumentLocationJson::decode),
                )
            },
            blocks = root.getValue("blocks").jsonArray.map { element ->
                val block = element.jsonObject
                ContentBlock(
                    id = block.required("id"),
                    sectionId = block.required("sectionId"),
                    type = BlockType.valueOf(block.required("type")),
                    text = block.required("text"),
                    order = block.getValue("order").jsonPrimitive.int,
                    anchor = DocumentLocationJson.decode(block.required("anchor")),
                    metadata = block["metadata"]?.jsonObject?.toStringMap().orEmpty(),
                )
            },
            metadata = root["metadata"]?.jsonObject?.toStringMap().orEmpty(),
        )
    }

    fun writeAtomically(document: CanonicalDocument, destination: File): File {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            temporary.writeText(encode(document), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun read(file: File): CanonicalDocument = decode(file.readText(Charsets.UTF_8))

    private fun stringMap(values: Map<String, String>) = buildJsonObject {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun kotlinx.serialization.json.JsonObject.required(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("$key is required")

    private fun kotlinx.serialization.json.JsonObject.optional(key: String): String? =
        get(key)?.jsonPrimitive?.content

    private fun kotlinx.serialization.json.JsonObject.toStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key to value.jsonPrimitive.content }
}
