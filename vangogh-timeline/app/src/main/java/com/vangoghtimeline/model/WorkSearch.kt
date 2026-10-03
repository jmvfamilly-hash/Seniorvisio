package com.vangoghtimeline.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.text.Normalizer

/** Un résultat de la recherche transversale : l'œuvre et l'artiste à qui elle appartient. */
class WorkHit(val artist: Artist, val artwork: Artwork)

/**
 * Recherche transversale : tous les mots de la requête doivent se trouver (sans accents ni casse) dans le texte de l'œuvre — artiste, titre, année,
 * lieu, matière, musée, détails de la fiche, sujet et technique déduits. « van gogh 1888 » trouve les œuvres de 1888 de Van Gogh ; « portrait huile »,
 * les portraits à l'huile de tous les peintres.
 */
object WorkSearch {
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun tokens(query: String): List<String> = fold(query).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun haystack(artist: Artist, a: Artwork): String {
        val meta = MetaTagger.tag(a)
        return fold(
            listOf(artist.name, a.title, a.date.year.toString(), a.place.orEmpty(), a.medium.orEmpty(), a.provider, meta.subject.label, meta.technique.label)
                .plus(a.details.map { it.second }).joinToString(" "),
        )
    }

    fun matches(artist: Artist, a: Artwork, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val h = haystack(artist, a)
        return tokens.all { it in h }
    }

    /** Les œuvres de tous les artistes qui répondent à [query] (vide si la requête n'a aucun mot). */
    fun search(query: String, works: Map<Artist, List<Artwork>>): List<WorkHit> {
        val t = tokens(query)
        if (t.isEmpty()) return emptyList()
        return works.flatMap { (artist, list) -> list.filter { matches(artist, it, t) }.map { WorkHit(artist, it) } }
    }
}

/** Recherches conservées : les plus récentes d'abord, sans doublon (casse et accents ignorés), au plus [MAX]. */
object SearchHistory {
    const val MAX = 20

    fun add(history: List<String>, query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (WorkSearch.tokens(q).isEmpty()) return history
        return (listOf(q) + history.filter { WorkSearch.fold(it) != WorkSearch.fold(q) }).take(MAX)
    }

    fun remove(history: List<String>, query: String): List<String> = history.filter { it != query }

    fun encode(history: List<String>): String = JsonArray(history.map { JsonPrimitive(it) }).toString()

    fun decode(text: String?): List<String> = runCatching {
        Json.parseToJsonElement(text ?: return emptyList()).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(MAX)
    }.getOrDefault(emptyList())
}

/** Parcours chronologique : l'indice de l'œuvre à montrer au pas suivant / précédent, ou en partant du milieu de l'écran. */
object ChronoTour {
    /** Œuvres dans l'ordre du temps (date, puis identifiant pour un ordre stable). */
    fun order(artworks: List<Artwork>): List<Artwork> = artworks.sortedWith(compareBy({ it.date.positionEpochDay }, { it.id }))

    /** [current] = -1 : aucun parcours en cours ; [start] = indice par lequel commencer. Le parcours s'arrête aux extrémités (renvoie [current]). */
    fun next(current: Int, size: Int, start: Int): Int = when {
        size == 0 -> -1
        current < 0 -> start.coerceIn(0, size - 1)
        else -> (current + 1).coerceAtMost(size - 1)
    }

    fun previous(current: Int, size: Int, start: Int): Int = when {
        size == 0 -> -1
        current < 0 -> start.coerceIn(0, size - 1)
        else -> (current - 1).coerceAtLeast(0)
    }
}
