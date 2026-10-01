package com.vangoghtimeline.iiif

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagLevel(val label: String) { ERROR("ERREUR"), WARN("ALERTE"), INFO("INFO") }

/**
 * Un événement du journal. [count] > 1 : des événements identiques (même [key]) ont été regroupés ; [timeMs] est le premier,
 * [lastMs] le dernier.
 */
class DiagEvent(
    val timeMs: Long,
    val level: DiagLevel,
    val category: String,
    val artistId: String?,
    val sourceId: String?,
    val message: String,
    val url: String?,
    val key: String,
) {
    var count: Int = 1
    var lastMs: Long = timeMs
}

/**
 * Journal de diagnostic de toute l'appli : chaque échec y est consigné, **même quand un repli le rattrape** (copie hors ligne,
 * deuxième variante de requête, nouvel essai d'une tuile) — c'est ce qui permet de voir ce qui est fragile alors que tout « marche ».
 *
 * - Mémoire seulement, borné à [MAX_EVENTS] événements (les plus anciens sortent) et à [MAX_MESSAGE] caractères par message.
 * - Les événements identiques proches sont regroupés (« ×37 ») : une salve de tuiles en échec ne chasse pas le reste du journal.
 * - [context] : l'artiste dont la frise est ouverte, attribué aux événements de navigation qui n'ont pas d'artiste explicite.
 *
 * Pur Kotlin, sûr entre fils : testé sur la JVM.
 */
object Diag {
    const val MAX_EVENTS = 600
    const val MAX_MESSAGE = 1200
    private const val MERGE_LOOKBACK = 40

    private val lock = Any()
    private val events = ArrayList<DiagEvent>()

    @Volatile var context: String? = null

    /** Horloge, remplaçable par les tests. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    fun log(
        level: DiagLevel, category: String, message: String,
        url: String? = null, sourceId: String? = null, artistId: String? = context,
        key: String = "$category|$sourceId|$artistId|$message|$url",
    ) {
        val now = clock()
        val text = if (message.length > MAX_MESSAGE) message.take(MAX_MESSAGE) + "…" else message
        synchronized(lock) {
            val from = (events.size - MERGE_LOOKBACK).coerceAtLeast(0)
            for (i in events.size - 1 downTo from) {
                val e = events[i]
                if (e.key == key && e.level == level) { e.count++; e.lastMs = now; return }
            }
            events += DiagEvent(now, level, category, artistId, sourceId, text, url, key)
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    fun error(category: String, message: String, url: String? = null, sourceId: String? = null, artistId: String? = context, key: String? = null) =
        log(DiagLevel.ERROR, category, message, url, sourceId, artistId, key ?: "$category|$sourceId|$artistId|$message|$url")

    fun warn(category: String, message: String, url: String? = null, sourceId: String? = null, artistId: String? = context, key: String? = null) =
        log(DiagLevel.WARN, category, message, url, sourceId, artistId, key ?: "$category|$sourceId|$artistId|$message|$url")

    fun info(category: String, message: String, url: String? = null, sourceId: String? = null, artistId: String? = context, key: String? = null) =
        log(DiagLevel.INFO, category, message, url, sourceId, artistId, key ?: "$category|$sourceId|$artistId|$message|$url")

    /** Copie des événements, du plus ancien au plus récent. */
    fun snapshot(): List<DiagEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    /** Hôte d'une URL (clé de regroupement : mille tuiles d'un même serveur en échec forment UNE ligne). */
    fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
}

/**
 * Bilan d'une analyse de réponse : combien d'éléments reçus, combien retenus, et POURQUOI les autres ont été écartés. C'est ce qui
 * permet de distinguer « le service ne renvoie rien » de « mon filtre écarte tout » (source vide).
 */
class Tally {
    var raw = 0
    var kept = 0
    private val reasons = LinkedHashMap<String, Int>()

    /** Forme de la réponse, renseignée quand rien n'est retenu (clés du premier niveau et du premier élément). */
    var shape: String? = null

    fun drop(reason: String, n: Int = 1) { if (n > 0) reasons[reason] = (reasons[reason] ?: 0) + n }

    fun summary(): String =
        "$raw reçues, $kept retenues" + if (reasons.isEmpty()) "" else " — écartées : " + reasons.entries.joinToString(", ") { "${it.value} ${it.key}" }

    /** Consigne le bilan : alerte si rien n'est retenu (avec la forme de la réponse), info sinon. */
    fun log(sourceId: String, artistId: String?, label: String? = null) {
        val text = "analyse${label?.let { " ($it)" } ?: ""} : ${summary()}" + if (kept == 0 && shape != null) " — forme de la réponse : $shape" else ""
        Diag.log(if (kept == 0) DiagLevel.WARN else DiagLevel.INFO, "source", text, sourceId = sourceId, artistId = artistId, key = "tally|$sourceId|$artistId|$text")
    }
}

/** Mise en forme du rapport d'anomalies copié dans le presse-papiers. Pur Kotlin. */
object DiagnosticsReport {
    private const val MAX_CHARS = 250_000

    /** Ce qu'on sait d'un artiste pour le rapport : ses sources configurées et, s'il a été préparé, leurs rapports. */
    class ArtistSection(val artistId: String, val artistName: String, val configuredSources: List<String>, val state: UniverseState?)

    fun build(
        header: String,
        sections: List<ArtistSection>,
        events: List<DiagEvent>,
        lastCrash: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val out = StringBuilder()
        out.append("=== Rapport d'anomalies — Van Gogh, frise ===\n")
        out.append("Date : ").append(full.format(Date(nowMs))).append('\n')
        out.append(header.trim()).append("\n\n")

        val errors = events.sumOf { if (it.level == DiagLevel.ERROR) it.count else 0 }
        val warns = events.sumOf { if (it.level == DiagLevel.WARN) it.count else 0 }
        out.append("Résumé : ${events.size} lignes de journal — $errors erreurs, $warns alertes (échecs rattrapés par un repli compris).\n\n")

        out.append("--- Univers (état des sources par artiste) ---\n")
        for (s in sections) {
            out.append("[${s.artistName}] ")
            val st = s.state
            if (s.configuredSources.isEmpty()) { out.append("grisé : aucune source configurée\n"); continue }
            if (st == null) { out.append("non préparé (sources : ${s.configuredSources.joinToString()})\n"); continue }
            out.append("${st.artworks.size} œuvres, ${st.connectedCount}/${st.reports.size} sources connectées${if (st.done) "" else " (chargement en cours)"}\n")
            for (r in st.reports) {
                out.append("   • ${r.name} [${r.sourceId}] ${r.state} — ${r.detail}\n")
                for (p in r.probes) out.append("       ${if (p.ok) "ok " else "ÉCHEC"} « ${p.artworkTitle} » ${p.url} → ${p.detail}\n")
            }
        }

        out.append("\n--- Journal (du plus ancien au plus récent) ---\n")
        if (events.isEmpty()) out.append("(vide)\n")
        for (e in events) {
            out.append(clock.format(Date(e.timeMs))).append(' ').append(e.level.label).append(' ').append(e.category)
            e.sourceId?.let { out.append('/').append(it) }
            e.artistId?.let { out.append(" [").append(it).append(']') }
            out.append(" : ").append(e.message)
            if (e.count > 1) out.append("  (×${e.count}, jusqu'à ${clock.format(Date(e.lastMs))})")
            e.url?.let { out.append("\n      ").append(it) }
            out.append('\n')
        }

        if (!lastCrash.isNullOrBlank()) {
            out.append("\n--- Dernier plantage enregistré ---\n").append(lastCrash.trim()).append('\n')
        }
        val text = out.toString()
        // trop long pour le presse-papiers : on garde la fin (les événements les plus récents)
        return if (text.length > MAX_CHARS) "[… rapport tronqué au début …]\n" + text.takeLast(MAX_CHARS) else text
    }
}
