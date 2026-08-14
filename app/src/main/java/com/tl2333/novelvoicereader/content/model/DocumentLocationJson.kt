package com.tl2333.novelvoicereader.content.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object DocumentLocationJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(location: DocumentLocation): String = buildJsonObject {
        put("sourceType", location.sourceType.name)
        location.sectionId?.let { put("sectionId", it) }
        location.blockId?.let { put("blockId", it) }
        location.charStart?.let { put("charStart", it) }
        location.charEnd?.let { put("charEnd", it) }
        location.href?.let { put("href", it) }
        location.readiumLocatorJson?.let { put("readiumLocatorJson", it) }
        location.page?.let { put("page", it) }
        location.paragraphIndex?.let { put("paragraphIndex", it) }
        location.snapshotId?.let { put("snapshotId", it) }
        location.contentHash?.let { put("contentHash", it) }
        location.bounds?.let { bounds ->
            put("bounds", buildJsonObject {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
        }
        put("metadata", buildJsonObject {
            location.metadata.toSortedMap().forEach { (key, value) -> put(key, value) }
        })
    }.toString()

    fun decode(value: String): DocumentLocation {
        val root = json.parseToJsonElement(value).jsonObject
        val bounds = root["bounds"]?.jsonObject?.let {
            DocumentBounds(
                left = requireNotNull(it.float("left")),
                top = requireNotNull(it.float("top")),
                right = requireNotNull(it.float("right")),
                bottom = requireNotNull(it.float("bottom")),
            )
        }
        return DocumentLocation(
            sourceType = SourceType.valueOf(root.string("sourceType") ?: error("sourceType is required")),
            sectionId = root.string("sectionId"),
            blockId = root.string("blockId"),
            charStart = root.int("charStart"),
            charEnd = root.int("charEnd"),
            href = root.string("href"),
            readiumLocatorJson = root.string("readiumLocatorJson"),
            page = root.int("page"),
            bounds = bounds,
            paragraphIndex = root.int("paragraphIndex"),
            snapshotId = root.string("snapshotId"),
            contentHash = root.string("contentHash"),
            metadata = root["metadata"]?.jsonObject?.entries?.associate { (key, element) ->
                key to element.jsonPrimitive.content
            }.orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.float(key: String): Float? = get(key)?.jsonPrimitive?.floatOrNull
}
