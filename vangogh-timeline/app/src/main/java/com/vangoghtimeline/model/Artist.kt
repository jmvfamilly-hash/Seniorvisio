package com.vangoghtimeline.model

import java.text.Normalizer

/** Les trois familles du catalogue (clés de `artists_by_movement`). */
enum class Movement(val key: String, val labelFr: String) {
    PRE_IMPRESSIONISM("pre_impressionism", "Avant l'impressionnisme"),
    IMPRESSIONISM("impressionism", "Impressionnisme"),
    POST_IMPRESSIONISM("post_impressionism", "Après l'impressionnisme"),
}

/** Un lieu de vie/travail, tel que décrit dans le catalogue. */
data class KeyLocation(val location: String, val workType: String, val period: String)

/** Où chercher les œuvres d'un artiste : un musée ([sourceId], voir `MuseumSource`) et, au besoin, son terme de recherche propre. */
data class SourceSpec(val sourceId: String, val term: String? = null)

/**
 * Un artiste du menu. Les champs descriptifs viennent du catalogue JSON (`assets/artists_by_movement.json`) ; les dates de vie, le
 * titre Wikipédia (portrait) et les [sources] viennent de [ArtistExtras].
 *
 * Un artiste n'a un **univers** (des œuvres qu'on peut ouvrir dans la frise) que si au moins une source est configurée : sinon, il
 * est grisé dans le menu.
 */
data class Artist(
    val id: String,
    val name: String,
    val origin: String,
    val activePeriod: String,
    val activeStart: Int?,
    val activeEnd: Int?,
    val mainStyle: String,
    val emblematicWork: String,
    val workType: String,
    val locations: List<KeyLocation>,
    val movement: Movement,
    val birthYear: Int? = null,
    val deathYear: Int? = null,
    val wikipediaTitle: String? = null,
    val sources: List<SourceSpec> = emptyList(),
) {
    val hasUniverse: Boolean get() = sources.isNotEmpty()

    /** « 1853–1890 », ou `null` si les dates de vie ne sont pas connues. */
    val lifespan: String? get() = if (birthYear != null && deathYear != null) "$birthYear–$deathYear" else null

    /** Initiales (portrait de secours) : « JS » pour John Singer Sargent → « JS » (première et dernière particule). */
    val initials: String
        get() {
            val parts = name.split(' ', '.', '-').filter { it.isNotBlank() && it[0].isLetter() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "${parts.first()[0]}${parts.last()[0]}".uppercase()
            }
        }
}

/** Minuscules, sans accents, tirets : stable pour un identifiant et pour comparer des noms. */
fun slugOf(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * Ce qu'on cherche pour un artiste dans les API des musées : son nom, le fragment de nom qui doit apparaître chez le créateur de
 * l'œuvre ([match], pour écarter les œuvres qui ne font que le citer) et la plage d'années plausibles ([years]).
 */
data class ArtworkQuery(val artistName: String, val match: String, val years: IntRange) {
    /** Vrai si [creator] (libellé du créateur chez le musée) désigne cet artiste : comparaison sans accents ni casse. */
    fun matchesCreator(creator: String): Boolean = slugOf(creator).contains(slugOf(match))

    companion object {
        val VAN_GOGH = ArtworkQuery("Vincent van Gogh", "gogh", 1870..1890)

        fun of(artist: Artist): ArtworkQuery {
            val last = artist.name.split(' ').last { it.isNotBlank() }
            val from = (artist.birthYear ?: artist.activeStart ?: 1800) + 10
            val to = (artist.deathYear ?: artist.activeEnd ?: 1950) + 1
            return ArtworkQuery(artist.name, last, from..to)
        }
    }
}
