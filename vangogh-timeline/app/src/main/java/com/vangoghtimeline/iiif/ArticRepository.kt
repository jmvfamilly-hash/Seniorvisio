package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import java.io.File

/** D'où viennent les œuvres affichées. */
sealed interface ArtworkSource {
    val artworks: List<Artwork>
    /** Réponse fraîche de l'API. */
    data class Online(override val artworks: List<Artwork>) : ArtworkSource
    /** Copie enregistrée lors d'une ouverture précédente (hors ligne). */
    data class Cached(override val artworks: List<Artwork>) : ArtworkSource
}

/**
 * Charge les œuvres de Van Gogh (Art Institute of Chicago) : réseau d'abord, puis la copie locale [cacheFile] si le réseau
 * échoue. La réponse de l'API est enregistrée telle quelle après chaque succès : la frise se rouvre sans réseau, avec ses images
 * (celles déjà vues sont dans le cache disque de Coil).
 *
 * Rend `null` quand il n'y a ni réseau ni copie : l'appelant prend alors le jeu de secours.
 */
class ArticRepository(
    private val source: ManifestSource,
    private val cacheFile: File,
    private val url: String = ArticParser.SEARCH_URL,
) {
    suspend fun load(): ArtworkSource? {
        try {
            val text = source.fetch(url)
            val artworks = ArticParser.parse(text)
            if (artworks.isNotEmpty()) {
                runCatching { cacheFile.writeText(text) }
                return ArtworkSource.Online(artworks)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // réseau indisponible : on essaie la copie locale
        }
        val cached = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()?.let(ArticParser::parse).orEmpty()
        return if (cached.isNotEmpty()) ArtworkSource.Cached(cached) else null
    }
}
