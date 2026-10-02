package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.DatePrecision
import com.vangoghtimeline.model.IiifRef
import com.vangoghtimeline.model.RightsCatalog
import com.vangoghtimeline.model.RightsInfo
import com.vangoghtimeline.model.RightsKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Copie locale d'une liste d'œuvres (cache hors ligne des musées dont la réponse brute n'est pas une liste simple). */
object ArtworkJson {
    fun encode(list: List<Artwork>): String = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id); put("title", a.title); put("provider", a.provider)
            put("year", a.date.year); put("month", a.date.month); put("day", a.date.day); put("precision", a.date.precision.name); if (a.date.estimated) put("de", true)
            a.place?.let { put("place", it) }; a.medium?.let { put("medium", it) }
            put("manifest", a.iiif.manifestUrl)
            a.iiif.imageServiceId?.let { put("service", it) }; a.iiif.thumbnailUrl?.let { put("thumb", it) }
            a.iiif.canvasWidth?.let { put("w", it) }; a.iiif.canvasHeight?.let { put("h", it) }
            a.iiif.imageUrl?.let { put("image", it) }
            a.rights?.let { r ->
                put("rk", r.kind.name); put("rl", r.label); put("rc", r.conditions)
                r.url?.let { put("ru", it) }; r.attribution?.let { put("ra", it) }
            }
        })
    }.toString()

    fun decode(text: String): List<Artwork> {
        val arr = try { Json.parseToJsonElement(text) as? JsonArray } catch (e: Exception) { null } ?: return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el as JsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
                fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
                Artwork(
                    id = s("id")!!, title = s("title")!!,
                    date = ArtworkDate(i("year")!!, i("month") ?: 1, i("day") ?: 1, DatePrecision.valueOf(s("precision")!!), estimated = (o["de"] as? JsonPrimitive)?.contentOrNull == "true"),
                    place = s("place"), medium = s("medium"),
                    iiif = IiifRef(s("manifest")!!, s("service"), s("thumb"), i("w"), i("h"), s("image")),
                    provider = s("provider").orEmpty(),
                    rights = s("rk")?.let { k ->
                        RightsInfo(RightsKind.valueOf(k), s("rl").orEmpty(), s("ru"), s("ra"), s("rc") ?: RightsCatalog.conditionsFor(RightsKind.valueOf(k)))
                    },
                )
            } catch (e: Exception) { null }
        }
    }
}
