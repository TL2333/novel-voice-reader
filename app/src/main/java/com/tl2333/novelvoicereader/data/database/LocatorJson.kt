package com.tl2333.novelvoicereader.data.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Validates while preserving the complete Readium Locator JSON object. */
object LocatorJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun canonicalize(value: String): String {
        val locator = parse(value)
        val href = locator["href"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(href.isNotEmpty()) { "Locator must contain a non-empty href" }
        return locator.toString()
    }

    fun parse(value: String): JsonObject = json.parseToJsonElement(value).jsonObject

    fun totalProgression(value: String): Double? =
        parse(value)["locations"]
            ?.jsonObject
            ?.get("totalProgression")
            ?.jsonPrimitive
            ?.doubleOrNull
            ?.coerceIn(0.0, 1.0)
}
