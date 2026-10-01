package com.vangoghtimeline.iiif

import com.iiifviewer.IiifManifestResolver
import com.iiifviewer.StaticImageUrl
import com.vangoghtimeline.iiif.JsonReading.int
import com.vangoghtimeline.iiif.JsonReading.obj
import com.vangoghtimeline.model.Artwork
import kotlinx.coroutines.CancellationException

/** Réponse d'un accès « léger » à une image : code HTTP et type de contenu (le corps n'est pas lu). */
class Reach(val status: Int, val contentType: String?)

/** Vérifie qu'une image ordinaire est joignable sans la télécharger (voir `HttpImageReachability` sur Android). */
fun interface ImageReachability {
    suspend fun check(url: String): Reach
}

/** Une vérification d'accès sur une œuvre échantillon. */
class ProbeLine(val artworkTitle: String, val url: String, val ok: Boolean, val detail: String)

/** Ce que vaut une source à la connexion. */
enum class SourceState(val connected: Boolean) {
    /** Cherchée, et accès IIIF/image vérifié sur un échantillon : ses œuvres sont dans la frise. */
    CONNECTED(true),
    /** Réseau indisponible : copie locale d'une connexion précédente validée. */
    CACHED(true),
    /** Cherchée, mais l'accès aux images échoue sur l'échantillon : ses œuvres sont REFUSÉES. */
    REJECTED(false),
    /** La recherche elle-même a échoué (réseau, 403, format inattendu). */
    UNREACHABLE(false),
    /** La recherche a réussi mais n'a rendu aucune œuvre exploitable pour cet artiste. */
    EMPTY(false),
    /** Source configurée mais non implémentée (ex. clé d'API requise). */
    UNAVAILABLE(false),
    /** En cours. */
    PENDING(false),
}

class SourceReport(val sourceId: String, val name: String, val state: SourceState, val count: Int, val detail: String, val probes: List<ProbeLine> = emptyList())

/**
 * Valide l'accès aux images AVANT de connecter une source : sur un échantillon de ses œuvres (réparties sur toute la liste), vérifie
 * que ce que le visualiseur va demander est vraiment lisible.
 *
 *  - œuvre à manifeste (AIC, Europeana) : le manifeste se lit, donne un service d'image, dont l'`info.json` donne une taille ; ou, sans
 *    service, une image ordinaire joignable ;
 *  - œuvre à service IIIF direct (Rijksmuseum, SMK) : son `info.json` se lit et donne une taille ;
 *  - œuvre à image ordinaire (Met, Cleveland) : l'URL répond 2xx avec un type `image/…`.
 *
 * La source est CONNECTÉE si au moins la moitié de l'échantillon passe ; sinon REFUSÉE, et le rapport dit pourquoi (code HTTP, URL).
 */
class SourceValidator(
    private val http: ManifestSource,
    private val reach: ImageReachability,
    private val sampleSize: Int = 3,
) {
    suspend fun validate(artworks: List<Artwork>): Pair<Boolean, List<ProbeLine>> {
        val sample = sample(artworks, sampleSize)
        if (sample.isEmpty()) return false to emptyList()
        val lines = sample.map { probe(it) }
        val needed = (sample.size + 1) / 2
        return (lines.count { it.ok } >= needed) to lines
    }

    /** [n] œuvres réparties de la première à la dernière (sans doublon). */
    internal fun sample(list: List<Artwork>, n: Int): List<Artwork> {
        if (list.isEmpty() || n <= 0) return emptyList()
        if (list.size <= n) return list
        return List(n) { i -> list[i * (list.size - 1) / (n - 1).coerceAtLeast(1)] }.distinct()
    }

    suspend fun probe(art: Artwork): ProbeLine {
        val url = art.iiif.viewerUrl ?: return ProbeLine(art.title, "", false, "aucune adresse d'image")
        return try {
            val detail = check(url)
            ProbeLine(art.title, url, true, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeLine(art.title, url, false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Rend une description de ce qui a été vérifié, ou lève une exception qui dit ce qui ne va pas. */
    private suspend fun check(url: String): String {
        StaticImageUrl.imageUrlOf(url)?.let { return checkImage(it) }
        val text = http.fetch(url)
        IiifManifestResolver.serviceIdOf(text)?.let { service -> return "manifeste → " + checkInfo(http.fetch(IiifManifestResolver.infoUrlFor(service))) }
        IiifManifestResolver.staticImageOf(text)?.let { return "manifeste → image ordinaire, " + checkImage(it.url) }
        return checkInfo(text)
    }

    /** `info.json` : doit donner une largeur et une hauteur strictement positives. */
    private fun checkInfo(text: String): String {
        val o = obj(text) ?: throw IllegalStateException("réponse qui n'est pas du JSON")
        val w = o.int("width") ?: 0
        val h = o.int("height") ?: 0
        if (w <= 0 || h <= 0) throw IllegalStateException("ni manifeste ni info.json exploitable (largeur/hauteur absentes)")
        return "info.json ${w}×$h"
    }

    private suspend fun checkImage(imageUrl: String): String {
        val r = reach.check(imageUrl)
        if (r.status !in 200..299) throw IllegalStateException("HTTP ${r.status} sur $imageUrl")
        val type = r.contentType?.lowercase()
        if (type != null && !type.startsWith("image/")) throw IllegalStateException("type « $type » au lieu d'une image : $imageUrl")
        return "image joignable"
    }
}
