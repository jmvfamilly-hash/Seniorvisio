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
    }

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
