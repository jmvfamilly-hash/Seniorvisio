package com.vangoghtimeline.iiif

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** La frise à afficher : les œuvres fusionnées, et le crédit (musées, nombre d'œuvres, copie hors ligne). */
class LoadedCollection(val artworks: List<Artwork>, val credit: String)

/**
 * Les œuvres de Van Gogh de trois musées, chargées EN PARALLÈLE et fusionnées au fil de leur arrivée : la frise s'affiche dès
 * que le premier musée a répondu, les autres s'y ajoutent ([onUpdate] est rappelé à chaque arrivée, avec la fusion complète).
 *
 * - Art Institute of Chicago ([ArticRepository]), Rijksmuseum ([RijksmuseumRepository]), Europeana ([EuropeanaRepository]).
 * - Un musée qui échoue (ou dépasse [timeoutMs]) ne retire rien aux autres.
 * - Doublons : Europeana agrège aussi le Rijksmuseum ; quand le Rijksmuseum a répondu directement, ses notices Europeana sont
 *   écartées, puis [ArtworkMerge] retire les œuvres de même titre et même année.
 *
 * Rend `null` si aucun musée n'a rien donné (ni réseau, ni copie) : l'appelant prend alors le jeu de secours.
 */
class CollectionLoader(
    private val source: ManifestSource,
    private val dir: File,
    private val timeoutMs: Long = 40_000,
) {
    suspend fun load(onUpdate: (LoadedCollection) -> Unit): LoadedCollection? = coroutineScope {
        var aic: ArtworkSource? = null
        var rijks: MuseumResult? = null
        var europeana: MuseumResult? = null
        var last: LoadedCollection? = null

        fun publish() {
            val rijksList = rijks?.artworks.orEmpty()
            val europeanaList = europeana?.artworks.orEmpty().let { list ->
                if (rijksList.isEmpty()) list else list.filterNot { it.provider.contains("rijksmuseum", ignoreCase = true) }
            }
            val merged = ArtworkMerge.merge(listOf(aic?.artworks.orEmpty(), rijksList, europeanaList))
            if (merged.isEmpty()) return
            val credit = listOfNotNull(
                aic?.let { label("Art Institute of Chicago", it.artworks.size, it is ArtworkSource.Cached) },
                rijks?.let { label("Rijksmuseum", it.artworks.size, it.cached) },
                europeana?.let { label("Europeana", europeanaList.size, it.cached) },
            ).joinToString(" · ")
            last = LoadedCollection(merged, credit)
            onUpdate(last!!)
        }

        val jobs = listOf(
            async { withTimeoutOrNull(timeoutMs) { ArticRepository(source, File(dir, "artic_vangogh.json")).load() }?.also { aic = it; publish() } },
            async { withTimeoutOrNull(timeoutMs) { RijksmuseumRepository(source, File(dir, "rijks_vangogh.json")).load() }?.also { rijks = it; publish() } },
            async { withTimeoutOrNull(timeoutMs) { EuropeanaRepository(source, File(dir, "europeana_vangogh.json")).load() }?.also { europeana = it; publish() } },
        )
        jobs.forEach { it.await() }
        last
    }

    private fun label(name: String, count: Int, cached: Boolean) = "$name ($count${if (cached) ", hors ligne" else ""})"
}
