package com.vangoghtimeline.ui

import androidx.compose.runtime.mutableStateMapOf
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.iiif.DiagnosticsReport
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

    /**
     * Prépare l'univers de cet artiste (une seule fois par lancement) : il s'affiche tout de suite depuis le magasin local, puis les
     * sources périmées (plus de 7 jours) ou en échec depuis plus d'une heure sont recherchées et validées en arrière-plan.
     */
    fun prepare(artist: Artist) {
        if (!artist.hasUniverse || !started.add(artist.id)) return
        launchLoad(artist, force = false)
    }

    /** « Actualiser » : tout est recherché et revalidé maintenant ; l'ancien univers reste affiché pendant ce temps. */
    fun refresh(artist: Artist) {
        if (!artist.hasUniverse) return
        started.add(artist.id)
        launchLoad(artist, force = true)
    }

    private fun launchLoad(artist: Artist, force: Boolean) {
        scope.launch {
            try {
                loader.load(artist, force) { universes[artist.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                started.remove(artist.id)   // une prochaine sélection réessaie
                Diag.error("magasin", "chargement de l'univers en échec : ${e.message ?: e.javaClass.simpleName}", artistId = artist.id)
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
                        val summaryUrl = WikipediaSummaryParser.summaryUrl(title)
                        val url = WikipediaSummaryParser.thumbnail(http.fetch(summaryUrl))
                        if (url != null) {
                            portraits[artist.id] = url
                            savePortraitFile()
                        } else {
                            Diag.warn("portrait", "l'article Wikipédia n'a pas d'image : portrait de secours (initiales)", summaryUrl, artistId = artist.id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // pas de portrait : la carte garde ses initiales, et l'échec est consigné
                        Diag.warn("portrait", "portrait de « ${artist.name} » indisponible : ${e.message ?: e.javaClass.simpleName}", artistId = artist.id)
                    }
                }
            }
        }
    }

    /** Rapport d'anomalies de TOUS les artistes et de TOUS les services, plus le journal de navigation (à copier dans le presse-papiers). */
    fun buildReport(artists: List<Artist>, header: String, lastCrash: String?): String =
        DiagnosticsReport.build(
            header = header,
            sections = artists.map { DiagnosticsReport.ArtistSection(it.id, it.name, it.sources.map { s -> s.sourceId }, universes[it.id]) },
            events = Diag.snapshot(),
            lastCrash = lastCrash,
        )

    private fun readPortraitFile(): Map<String, String> = runCatching {
        val o = Json.parseToJsonElement(portraitFile.readText()) as JsonObject
        o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private suspend fun savePortraitFile() = withContext(Dispatchers.IO) {
        runCatching { portraitFile.writeText(buildJsonObject { portraits.forEach { (k, v) -> put(k, v) } }.toString()) }
    }
}
