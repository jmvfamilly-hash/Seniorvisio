package com.vangoghtimeline.iiif

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Lecture tolérante d'un arbre JSON : tout champ absent ou d'un autre type donne `null`, jamais une exception. */
internal object JsonReading {
    private val json = Json { ignoreUnknownKeys = true }

    fun obj(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    fun JsonObject.objOf(key: String): JsonObject? = this[key] as? JsonObject
    fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    /** Chaînes d'un champ qui peut être une chaîne ou un tableau de chaînes. */
    fun JsonObject.strings(key: String): List<String> = when (val e = this[key]) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.contentOrNull)
        else -> emptyList()
    }
}
