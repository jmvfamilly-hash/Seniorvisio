package com.vangoghtimeline.ui

import androidx.compose.runtime.mutableStateMapOf
import com.vangoghtimeline.iiif.ManifestSource
import com.vangoghtimeline.iiif.UniverseLoader
import com.vangoghtimeline.iiif.UniverseState
import com.vangoghtimeline.iiif.WikipediaSummaryParser
import com.vangoghtimeline.model.Artist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * État de l'application : l'univers de chaque artiste (préparé quand on le SÉLECTIONNE, pour que l'ouverture suivante soit immédiate)
 * et les portraits (adresse de la vignette Wikipédia, mémorisée sur disque).
 *
 * Tout tourne sur le thread principal (`scope` = Main) ; réseau et disque sont déportés par les sources.
 */
class AppModel(
    private val scope: CoroutineScope,
    private val loader: UniverseLoader,
    private val http: ManifestSource,
    private val portraitFile: File,
) {
    /** Lus par la composition : toute mise à jour redessine le menu. */
    val universes = mutableStateMapOf<String, UniverseState>()
    val portraits = mutableStateMapOf<String, String>()

    private val started = HashSet<String>()

    /** Lance (une seule fois) la connexion des sources de cet artiste, avec validation de leur accès IIIF. */
    fun prepare(artist: Artist) {
        if (!artist.hasUniverse || !started.add(artist.id)) return
        scope.launch {
            try {
                loader.load(artist) { universes[artist.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                started.remove(artist.id)   // une prochaine sélection réessaie
            }
        }
    }

    /** Charge les portraits manquants (4 requêtes à la fois). */
    fun loadPortraits(artists: List<Artist>) {
        readPortraitFile().forEach { (id, url) -> portraits[id] = url }
        val gate = Semaphore(4)
        for (artist in artists) {
            val title = artist.wikipediaTitle ?: continue
            if (portraits[artist.id] != null) continue
            scope.launch {
                gate.withPermit {
                    try {
                        val url = WikipediaSummaryParser.thumbnail(http.fetch(WikipediaSummaryParser.summaryUrl(title)))
                        if (url != null) {
                            portraits[artist.id] = url
                            savePortraitFile()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // pas de portrait : la carte garde ses initiales
                    }
                }
            }
        }
    }

    private fun readPortraitFile(): Map<String, String> = runCatching {
        val o = Json.parseToJsonElement(portraitFile.readText()) as JsonObject
        o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private suspend fun savePortraitFile() = withContext(Dispatchers.IO) {
        runCatching { portraitFile.writeText(buildJsonObject { portraits.forEach { (k, v) -> put(k, v) } }.toString()) }
    }
}
