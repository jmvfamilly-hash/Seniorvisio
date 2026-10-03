package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Frontière réseau : rend le texte d'une URL. Implémentée par plateforme (voir `HttpManifestSource` sur Android). */
fun interface ManifestSource {
    suspend fun fetch(url: String): String
}

/**
 * Charge des manifestes IIIF et en fait des [Artwork].
 *
 * - [loadCollection] lit une **Collection** IIIF Presentation 3 (`items` de type `Manifest`), puis chaque manifeste.
 * - Les manifestes sont lus en parallèle, dans la limite de [parallelism] : des dizaines de requêtes ne saturent ni le
 *   serveur ni la radio du téléphone.
 * - Un manifeste illisible ou sans date est ignoré (et signalé à [onSkipped]) ; il ne fait pas échouer la frise entière.
 */
class ManifestRepository(
    private val source: ManifestSource,
    private val parallelism: Int = 4,
) {
    suspend fun loadCollection(collectionUrl: String, onSkipped: (url: String, reason: String) -> Unit = { _, _ -> }): List<Artwork> {
        val root = Json.parseToJsonElement(source.fetch(collectionUrl)) as? JsonObject ?: return emptyList()
        val urls = (root["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull
            if (type != null && type != "Manifest") null else (o["id"] as? JsonPrimitive)?.contentOrNull
        }
        return loadManifests(urls, onSkipped)
    }

    suspend fun loadManifests(urls: List<String>, onSkipped: (url: String, reason: String) -> Unit = { _, _ -> }): List<Artwork> =
        coroutineScope {
            val gate = Semaphore(parallelism)
            urls.map { url ->
                async {
                    gate.withPermit {
                        try {
                            IiifManifestParser.parse(source.fetch(url), url)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onSkipped(url, e.message ?: e.javaClass.simpleName)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))
        }
}
