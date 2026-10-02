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
class ProbeLine(val artworkTitle: String, val url: String, val ok: Boolean, val detail: String,
                /** Échec dû au réseau ou à une limite de débit (429, 503, délai, DNS…), pas à l'image elle-même : il ne prouve rien contre la source. */
                val transient: Boolean = false)

/** Distingue un échec de RÉSEAU (qui ne prouve rien) d'un vrai refus d'accès (404, 403 JSON, type inattendu…). */
object NetworkTolerance {
    fun isTransient(message: String?): Boolean {
        if (message == null) return false
        if (TemporaryBlock.matches(message)) return true
        return listOf("timeout", "timed out", "délai dépassé", "unable to resolve host", "unknownhost", "no address associated", "connection reset",
            "connection refused", "failed to connect", "unexpected end of stream", "socket closed", "canceled", "software caused connection abort",
            "network is unreachable", "ssl").any { message.contains(it, ignoreCase = true) }
    }
}

/** Ce que vaut une source à la connexion. */
enum class SourceState(val connected: Boolean) {
    /** Cherchée, et accès IIIF/image vérifié sur un échantillon : ses œuvres sont dans la frise. */
    CONNECTED(true),
    /** Connectée et validée, mais la lecture n'est pas finie (ex. Met : des centaines de notices lues par tranches) : la suite vient au prochain chargement. */
    PARTIAL(true),
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
    /** Bloquée temporairement (pare-feu anti-robot, limite de débit) : nouvel essai automatique plus tard. */
    LIMITED(false),
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
    /** Pause avant l'unique nouvel essai d'un échantillon en échec de réseau (remplaçable par les tests). */
    private val retryPauseMs: Long = 1_500,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    suspend fun validate(artworks: List<Artwork>): Pair<Boolean, List<ProbeLine>> = decide(artworks).let { it.ok to it.lines }

    /** Résultat détaillé : [inconclusive] = aucun échantillon n'a pu être jugé (tous en échec de réseau) → ni connectée, ni refusée. */
    class Verdict(val ok: Boolean, val inconclusive: Boolean, val lines: List<ProbeLine>)

    /**
     * Un échantillon en échec de RÉSEAU (429, délai, DNS…) est retenté une fois, puis NE COMPTE PAS contre la source : la moitié des
     * échantillons CONCLUANTS doit passer. Si aucun n'est concluant, le verdict est « indéterminé » (la source sera réessayée plus tard).
     */
    suspend fun decide(artworks: List<Artwork>): Verdict {
        val sample = sample(artworks, sampleSize)
        if (sample.isEmpty()) return Verdict(false, false, emptyList())
        val lines = sample.map { art ->
            val first = probe(art)
            if (!first.ok && first.transient) { sleep(retryPauseMs); probe(art) } else first
        }
        val conclusive = lines.filter { !it.transient || it.ok }
        if (conclusive.isEmpty()) return Verdict(false, true, lines)
        val needed = (conclusive.size + 1) / 2
        return Verdict(conclusive.count { it.ok } >= needed, false, lines)
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
            val why = e.message ?: e.javaClass.simpleName
            ProbeLine(art.title, url, false, why, transient = NetworkTolerance.isTransient(why) || NetworkTolerance.isTransient(e.javaClass.simpleName))
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
