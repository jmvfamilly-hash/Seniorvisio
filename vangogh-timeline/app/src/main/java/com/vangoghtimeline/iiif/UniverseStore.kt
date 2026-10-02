package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Ce qu'on garde d'UNE source pour UN artiste : son état de la dernière tentative, les œuvres obtenues (si connectée) et le rapport de
 * validation. [fetchedAt] : quand les œuvres ont été obtenues ; [attemptedAt] : dernière tentative (réussie ou non).
 */
class StoredSource(
    val name: String,
    val state: SourceState,
    val detail: String,
    val count: Int,
    val fetchedAt: Long,
    val attemptedAt: Long,
    val artworks: List<Artwork>,
    val probes: List<ProbeLine>,
) {
    fun toReport(sourceId: String) = SourceReport(sourceId, name, state, count, detail, probes)
}

/** L'univers d'un artiste tel qu'enregistré : une entrée par source, et la version des règles de lecture qui l'ont produit. */
class StoredUniverse(val parserVersion: Int, val sources: Map<String, StoredSource>)

/**
 * Que faire d'une source enregistrée à l'ouverture d'un artiste (voir [UniverseLoader]) :
 *
 *  - [FRESH] : on s'en sert telle quelle, sans réseau ;
 *  - [REFRESH] : on s'en sert tout de suite ET on la rafraîchit en arrière-plan ;
 *  - [FETCH] : rien d'utilisable, on la cherche.
 */
enum class StoreDecision { FRESH, REFRESH, FETCH }

object StorePolicy {
    /** Une connexion validée reste bonne 7 jours : les collections des musées bougent peu. */
    const val FRESH_MS = 7L * 24 * 3_600_000

    /** Un échec (refusée, injoignable, vide, limitée…) ou une copie hors ligne est retenté au bout d'une heure. */
    const val RETRY_MS = 3_600_000L

    fun decide(stored: StoredSource?, nowMs: Long, force: Boolean = false): StoreDecision {
        if (stored == null) return StoreDecision.FETCH
        val usable = stored.state.connected && stored.artworks.isNotEmpty()
        if (force) return if (usable) StoreDecision.REFRESH else StoreDecision.FETCH
        return when (stored.state) {
            SourceState.CONNECTED -> {
                val age = nowMs - stored.fetchedAt
                if (age in 0 until FRESH_MS) StoreDecision.FRESH else StoreDecision.REFRESH      // horloge reculée : on rafraîchit
            }
            SourceState.CACHED -> if (nowMs - stored.attemptedAt in 0 until RETRY_MS) StoreDecision.FRESH else StoreDecision.REFRESH
            SourceState.PENDING -> StoreDecision.FETCH
            else -> if (nowMs - stored.attemptedAt in 0 until RETRY_MS) StoreDecision.FRESH else StoreDecision.FETCH
        }
    }
}

/**
 * Magasin JSON local : un fichier par artiste (`{dir}/{artistId}.json`), lu à l'ouverture pour afficher l'univers sans réseau.
 *
 * [PARSER_VERSION] : à incrémenter dès que les règles de lecture ou de filtrage changent (créateurs, dates, domaine public…) : les
 * fichiers d'une autre version sont ignorés, sinon d'anciennes œuvres mal filtrées resteraient 7 jours.
 */
object UniverseStore {
    /** 2 : filtres relâchés (œuvres protégées consultables) et licences enregistrées avec chaque œuvre. */
    const val PARSER_VERSION = 3

    private val json = Json { ignoreUnknownKeys = true }

    fun fileOf(dir: File, artistId: String) = File(dir, "$artistId.json")

    /** Rend `null` si le fichier manque, est illisible, ou date d'une autre version des règles. */
    fun read(dir: File, artistId: String): StoredUniverse? {
        val text = runCatching { fileOf(dir, artistId).takeIf { it.exists() }?.readText() }.getOrNull() ?: return null
        return decode(text)?.takeIf { it.parserVersion == PARSER_VERSION }
    }

    /** Écriture atomique (fichier temporaire puis renommage) : une interruption ne laisse jamais un fichier tronqué. */
    private val writeLock = Any()

    fun write(dir: File, artistId: String, universe: StoredUniverse) {
        // un seul écrivain à la fois : plusieurs sources qui finissent ensemble écrivaient le MÊME fichier temporaire
        synchronized(writeLock) {
            runCatching {
                dir.mkdirs()
                val target = fileOf(dir, artistId)
                val tmp = File(dir, "$artistId.json.tmp")
                tmp.writeText(encode(universe))
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            }
        }
    }

    fun encode(u: StoredUniverse): String = buildJsonObject {
        put("parserVersion", u.parserVersion)
        put("sources", buildJsonObject {
            for ((id, s) in u.sources) put(id, buildJsonObject {
                put("name", s.name); put("state", s.state.name); put("detail", s.detail); put("count", s.count)
                put("fetchedAt", s.fetchedAt); put("attemptedAt", s.attemptedAt)
                put("artworks", json.parseToJsonElement(ArtworkJson.encode(s.artworks)))
                put("probes", buildJsonArray {
                    for (p in s.probes) add(buildJsonObject { put("title", p.artworkTitle); put("url", p.url); put("ok", p.ok); put("detail", p.detail) })
                })
            })
        })
    }.toString()

    fun decode(text: String): StoredUniverse? = try {
        val root = json.parseToJsonElement(text) as JsonObject
        val version = (root["parserVersion"] as JsonPrimitive).content.toInt()
        val sources = (root["sources"] as JsonObject).mapNotNull { (id, el) ->
            try {
                val o = el as JsonObject
                fun s(k: String) = (o[k] as JsonPrimitive).contentOrNull ?: ""
                fun l(k: String) = (o[k] as JsonPrimitive).longOrNull ?: 0L
                val probes = (o["probes"] as? JsonArray).orEmpty().mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    fun ps(k: String) = (po[k] as? JsonPrimitive)?.contentOrNull ?: ""
                    ProbeLine(ps("title"), ps("url"), (po["ok"] as? JsonPrimitive)?.booleanOrNull ?: false, ps("detail"))
                }
                id to StoredSource(
                    name = s("name"), state = SourceState.valueOf(s("state")), detail = s("detail"), count = s("count").toInt(),
                    fetchedAt = l("fetchedAt"), attemptedAt = l("attemptedAt"),
                    artworks = ArtworkJson.decode(o["artworks"].toString()), probes = probes,
                )
            } catch (e: Exception) { null }
        }.toMap()
        StoredUniverse(version, sources)
    } catch (e: Exception) { null }
}
