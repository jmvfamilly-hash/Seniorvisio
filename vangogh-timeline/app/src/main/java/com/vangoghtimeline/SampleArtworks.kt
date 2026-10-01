package com.vangoghtimeline

import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.ArtworkDate
import com.vangoghtimeline.model.IiifRef

/**
 * Jeu de démonstration, utilisable hors ligne (aucune vignette : la frise affiche des aplats aux couleurs du lieu).
 *
 * ATTENTION : les dates sont celles des chronologies usuelles, **au mois près** (précision `MONTH`), écrites de mémoire
 * pour la démonstration. Elles ne remplacent pas les données d'un musée : en production, la date vient de `navDate`
 * dans le manifeste IIIF (voir [com.vangoghtimeline.iiif.IiifManifestParser]), au jour près quand l'institution la connaît.
 */
object SampleArtworks {
    private fun w(id: String, title: String, y: Int, m: Int, place: String, medium: String = "Huile sur toile") =
        Artwork(
            id = id, title = title, date = ArtworkDate.month(y, m), place = place, medium = medium,
            iiif = IiifRef(manifestUrl = "demo:vangogh/$id"), // manifeste hors ligne : voir demo/DemoIiifSources
        )

    val all: List<Artwork> = listOf(
        w("s01", "Sorrow", 1882, 4, "La Haye", "Crayon et encre"),
        w("s02", "Les Mangeurs de pommes de terre", 1885, 4, "Nuenen"),
        w("s03", "Nature morte à la Bible ouverte", 1885, 10, "Nuenen"),
        w("s04", "Tête de squelette à la cigarette", 1886, 1, "Anvers"),
        w("s05", "Agostina Segatori au café du Tambourin", 1887, 2, "Paris"),
        w("s06", "Autoportrait au chapeau de paille", 1887, 8, "Paris"),
        w("s07", "Le Pont sous la pluie (d'après Hiroshige)", 1887, 9, "Paris"),
        w("s08", "Portrait du père Tanguy", 1887, 10, "Paris"),
        w("s09", "Autoportrait au chapeau de feutre gris", 1888, 1, "Paris"),
        w("s10", "Pêchers en fleurs", 1888, 3, "Arles"),
        w("s11", "Le Pont de Langlois à Arles", 1888, 3, "Arles"),
        w("s12", "Le Zouave", 1888, 6, "Arles"),
        w("s13", "Bateaux aux Saintes-Maries", 1888, 6, "Arles"),
        w("s14", "Les Tournesols", 1888, 8, "Arles"),
        w("s15", "Le Café de nuit", 1888, 9, "Arles"),
        w("s16", "Terrasse du café le soir", 1888, 9, "Arles"),
        w("s17", "La Nuit étoilée sur le Rhône", 1888, 9, "Arles"),
        w("s18", "La Maison jaune", 1888, 9, "Arles"),
        w("s19", "La Chambre de Van Gogh à Arles", 1888, 10, "Arles"),
        w("s20", "La Chaise de Van Gogh", 1888, 11, "Arles"),
        w("s21", "La Vigne rouge", 1888, 11, "Arles"),
        w("s22", "La Chaise de Gauguin", 1888, 11, "Arles"),
        w("s23", "La Berceuse", 1888, 12, "Arles"),
        w("s24", "Autoportrait à l'oreille bandée", 1889, 1, "Arles"),
        w("s25", "Portrait du docteur Félix Rey", 1889, 1, "Arles"),
        w("s26", "Les Iris", 1889, 5, "Saint-Rémy-de-Provence"),
        w("s27", "La Nuit étoilée", 1889, 6, "Saint-Rémy-de-Provence"),
        w("s28", "Cyprès", 1889, 6, "Saint-Rémy-de-Provence"),
        w("s29", "Autoportrait (musée d'Orsay)", 1889, 9, "Saint-Rémy-de-Provence"),
        w("s30", "Le Mûrier", 1889, 10, "Saint-Rémy-de-Provence"),
        w("s31", "La Sieste (d'après Millet)", 1890, 1, "Saint-Rémy-de-Provence"),
        w("s32", "Amandier en fleurs", 1890, 2, "Saint-Rémy-de-Provence"),
        w("s33", "La Ronde des prisonniers", 1890, 2, "Saint-Rémy-de-Provence"),
        w("s34", "Roses", 1890, 5, "Saint-Rémy-de-Provence"),
        w("s35", "Portrait du docteur Gachet", 1890, 6, "Auvers-sur-Oise"),
        w("s36", "L'Église d'Auvers", 1890, 6, "Auvers-sur-Oise"),
        w("s37", "Paysage avec voiture et train", 1890, 6, "Auvers-sur-Oise"),
        w("s38", "Le Jardin de Daubigny", 1890, 7, "Auvers-sur-Oise"),
        w("s39", "Racines d'arbres", 1890, 7, "Auvers-sur-Oise"),
        w("s40", "Champ de blé aux corbeaux", 1890, 7, "Auvers-sur-Oise"),
    )
}
