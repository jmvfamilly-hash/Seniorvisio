package com.iiifviewer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Passe d'un **manifeste** IIIF (Presentation API 2 ou 3) à l'**image** qu'il porte.
 *
 * Le visualiseur lit une `info.json` d'Image API ; or une galerie manipule des manifestes. Ce résolveur trouve, dans le manifeste,
 * le service d'image du premier canevas :
 *  - Presentation 3 : `items[0].items[0].items[0].body.service[0].id`
 *  - Presentation 2 : `sequences[0].canvases[0].images[0].resource.service["@id"]`
 */
object IiifManifestResolver {

    /** URL de base du service d'image (sans `/info.json`), ou `null` si [json] n'est pas un manifeste ou n'en contient pas. */
    fun serviceIdOf(json: String): String? {
        val root = try { JSONObject(json) } catch (e: Exception) { return null }
        val type = root.optString("type").ifEmpty { root.optString("@type") }
        if (!type.contains("Manifest", ignoreCase = true)) return null

        val v3Body = root.optJSONArray("items")?.obj(0)?.optJSONArray("items")?.obj(0)?.optJSONArray("items")?.obj(0)?.opt("body")
        val v2Resource = root.optJSONArray("sequences")?.obj(0)?.optJSONArray("canvases")?.obj(0)
            ?.optJSONArray("images")?.obj(0)?.opt("resource")
        return serviceOf(v3Body) ?: serviceOf(v2Resource)
            // pas de service déclaré, mais l'image du manifeste est elle-même une URL d'image IIIF : on en déduit le service
            ?: imageUrlOf(v3Body ?: v2Resource)?.let(::serviceBaseOfImageUrl)
    }

    /**
     * Image « simple » du premier canevas (ni service IIIF, ni URL d'image IIIF) : un fichier JPEG/PNG ordinaire. Le visualiseur la
     * découpe lui-même en tuiles ([StaticImageUrl]). [width]/[height] viennent du manifeste quand il les donne.
     */
    class StaticImage(val url: String, val width: Int?, val height: Int?)

    fun staticImageOf(json: String): StaticImage? {
        val root = try { JSONObject(json) } catch (e: Exception) { return null }
        val type = root.optString("type").ifEmpty { root.optString("@type") }
        if (!type.contains("Manifest", ignoreCase = true)) return null
        val v3Canvas = root.optJSONArray("items")?.obj(0)
        val v3Body = v3Canvas?.optJSONArray("items")?.obj(0)?.optJSONArray("items")?.obj(0)?.opt("body")
        val v2Canvas = root.optJSONArray("sequences")?.obj(0)?.optJSONArray("canvases")?.obj(0)
        val v2Resource = v2Canvas?.optJSONArray("images")?.obj(0)?.opt("resource")
        val raw = v3Body ?: v2Resource
        val resource = (raw as? JSONObject) ?: (raw as? JSONArray)?.obj(0) ?: return null
        val canvas = if (v3Body != null) v3Canvas else v2Canvas
        val url = imageUrlOf(resource)?.takeIf { it.startsWith("http") } ?: return null
        fun dim(key: String): Int? = resource.optInt(key, 0).takeIf { it > 0 } ?: canvas?.optInt(key, 0)?.takeIf { it > 0 }
        return StaticImage(url, dim("width"), dim("height"))
    }

    private fun imageUrlOf(resource: Any?): String? {
        val res = (resource as? JSONObject) ?: (resource as? JSONArray)?.obj(0) ?: return null
        return res.optString("id").ifEmpty { res.optString("@id") }.ifEmpty { null }
    }

    private val imageUrlPattern = Regex("""^(https?://.+)/[^/]+/[^/]+/!?\d+(?:\.\d+)?/(?:default|color|gray|bitonal|native)\.[A-Za-z]+$""")

    /** `{service}/{region}/{size}/{rotation}/{quality}.{format}` → `{service}` ; `null` si [url] n'a pas cette forme. */
    fun serviceBaseOfImageUrl(url: String): String? = imageUrlPattern.matchEntire(url.substringBefore('?').trim())?.groupValues?.get(1)

    /** `info.json` correspondante. */
    fun infoUrlFor(serviceId: String): String = serviceId.trimEnd('/') + "/info.json"

    private fun serviceOf(resource: Any?): String? {
        val res = (resource as? JSONObject) ?: (resource as? JSONArray)?.obj(0) ?: return null
        val service = res.opt("service")
        val s = (service as? JSONObject) ?: (service as? JSONArray)?.obj(0) ?: return null
        return s.optString("id").ifEmpty { s.optString("@id") }.ifEmpty { null }
    }

    private fun JSONArray.obj(i: Int): JSONObject? = optJSONObject(i)
}
