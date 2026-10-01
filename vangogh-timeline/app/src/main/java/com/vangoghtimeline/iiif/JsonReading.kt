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

    /** Racine d'une réponse (objet ou tableau), ou `null` si ce n'est pas du JSON. */
    fun root(text: String): JsonElement? = try { json.parseToJsonElement(text) } catch (e: Exception) { null }

    fun obj(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

    /** « clés [total, objectIDs] ; premier élément de « items » : [id, title…] » : de quoi reconnaître la forme d'une réponse inattendue. */
    fun describeShape(text: String): String {
        val root = root(text) ?: return "non JSON (${text.take(80).replace('\n', ' ')})"
        val keys = (root as? JsonObject)?.keys?.joinToString() ?: "tableau de ${(root as? JsonArray)?.size ?: 0}"
        val firstList = (root as? JsonObject)?.entries?.firstOrNull { (it.value as? JsonArray)?.isNotEmpty() == true }
        val firstItem = ((firstList?.value ?: root) as? JsonArray)?.firstOrNull() as? JsonObject
        return "clés [$keys]" + if (firstItem != null) " ; premier élément de « ${firstList?.key ?: "racine"} » : [${firstItem.keys.joinToString()}]" else ""
    }

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
