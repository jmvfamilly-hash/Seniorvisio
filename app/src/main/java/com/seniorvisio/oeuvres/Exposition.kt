package com.seniorvisio.oeuvres

import android.util.Log
import org.json.JSONObject

/**
 * Une exposition commentée, et la visite qu'on en fait.
 *
 * ═══ POURQUOI CE N'EST PAS UNE LISTE DE PHOTOS ═══
 *
 * Le JSON ne décrit pas des images : il décrit un PARCOURS. Chaque œuvre
 * porte des points d'intérêt, chacun avec sa position sur la toile et le
 * commentaire d'un conservateur.
 *
 * Cela change tout pour Jean. Une galerie de photos demanderait un geste
 * pour agrandir — pincement à deux doigts, glissement pour déplacer — c'est-
 * à-dire exactement ce que ce projet s'interdit. Ici, le zoom est DIRIGÉ : il
 * appuie sur Suivant, la vue se pose sur le détail suivant, et le texte
 * arrive avec. Aucun geste à apprendre, et il voit la touche du pinceau.
 *
 * ═══ UNE SEULE SÉQUENCE, À PLAT ═══
 *
 * Deux niveaux de navigation — entre les œuvres, puis dans chaque œuvre —
 * demanderaient deux paires de boutons, ou un bouton qui change de sens selon
 * l'endroit. Les deux sont hors de question sur cet écran.
 *
 * Le parcours est donc aplati en une seule liste d'étapes :
 *
 *     Œuvre 1 — vue d'ensemble
 *       ↳ Les pêcheuses
 *       ↳ Le ciel nuageux
 *     Œuvre 2 — vue d'ensemble
 *       ↳ …
 *
 * Précédent et Suivant la parcourent, et rien d'autre n'existe. C'est la même
 * mécanique que le fil d'information, donc les mêmes boutons, le même
 * glissement et le même grisage aux extrémités.
 */
data class PointInteret(
    val cible: String,
    /** Position sur la toile, de 0 à 1, origine en haut à gauche. */
    val x: Float,
    val y: Float,
    val texte: String,
)

data class Oeuvre(
    val titre: String,
    val annee: Int?,
    val musee: String?,
    val points: List<PointInteret>,
) {
    /** « En route pour la pêche (1878) » — ce qui s'affiche sous la toile. */
    val légende: String
        get() = if (annee != null) "$titre ($annee)" else titre
}

data class Exposition(
    val titre: String,
    val curateur: String?,
    val oeuvres: List<Oeuvre>,
) {
    companion object {
        private const val TAG = "Exposition"

        /**
         * Lit le JSON collé par l'administrateur, ou rend null.
         *
         * TOUT EST ENVELOPPÉ, et rien n'est exigé au-delà du minimum : ce texte
         * est saisi à la main dans un champ, donc il sera un jour tronqué,
         * mal collé, ou porteur d'une virgule en trop. Un JSON refusé doit
         * laisser l'écran d'accueil tel qu'il était, jamais le casser.
         *
         * Une œuvre sans titre est écartée ; un point sans coordonnées aussi.
         * Le compte des écartés est journalisé par l'appelant : une exposition
         * qui perd la moitié de ses points doit se voir, au lieu de paraître
         * simplement courte.
         */
        fun depuisJson(texte: String): Exposition? {
            if (texte.isBlank()) return null
            return try {
                val racine = JSONObject(texte)
                val tableau = racine.optJSONArray("oeuvres") ?: return null
                val oeuvres = mutableListOf<Oeuvre>()
                for (i in 0 until tableau.length()) {
                    val o = tableau.optJSONObject(i) ?: continue
                    val titre = o.optString("titre").takeIf { it.isNotBlank() } ?: continue
                    val points = mutableListOf<PointInteret>()
                    val pts = o.optJSONArray("points_interet")
                    for (j in 0 until (pts?.length() ?: 0)) {
                        val p = pts?.optJSONObject(j) ?: continue
                        // has() et non optDouble() seul : optDouble rend 0.0
                        // pour une clé absente, et 0.0 est une position VALIDE
                        // — le coin supérieur gauche. Un point sans coordonnées
                        // se serait donc affiché sur le coin de la toile au
                        // lieu d'être écarté.
                        if (!p.has("x") || !p.has("y")) continue
                        points += PointInteret(
                            cible = p.optString("cible"),
                            x = p.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                            y = p.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                            texte = p.optString("texte"),
                        )
                    }
                    oeuvres += Oeuvre(
                        titre = titre,
                        annee = o.optInt("annee").takeIf { it > 0 },
                        musee = o.optString("musee").takeIf { it.isNotBlank() },
                        points = points,
                    )
                }
                if (oeuvres.isEmpty()) return null
                Exposition(
                    titre = racine.optString("exposition").takeIf { it.isNotBlank() } ?: "Exposition",
                    curateur = racine.optString("curateur").takeIf { it.isNotBlank() },
                    oeuvres = oeuvres,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Exposition illisible", e)
                null
            }
        }
    }
}

/** Une étape du parcours : une toile entière, ou un détail commenté. */
sealed class Étape {
    abstract val rangOeuvre: Int
    abstract val oeuvre: Oeuvre

    /** La toile en entier, pour situer avant d'entrer dans le détail. */
    data class VueDEnsemble(override val rangOeuvre: Int, override val oeuvre: Oeuvre) : Étape()

    data class Detail(
        override val rangOeuvre: Int,
        override val oeuvre: Oeuvre,
        val point: PointInteret,
    ) : Étape()

    /** Ce qui s'écrit sous l'image, à cette étape. */
    val légende: String
        get() = when (this) {
            is VueDEnsemble -> listOfNotNull(oeuvre.légende, oeuvre.musee).joinToString(" · ")
            is Detail -> listOfNotNull(point.cible.takeIf { it.isNotBlank() }, point.texte)
                .joinToString(" — ")
        }
}

/**
 * Le parcours aplati, et le seul objet que l'écran manipule.
 *
 * Sans état d'affichage : il ne connaît ni vue, ni bitmap, ni fichier. C'est
 * ce qui permet de l'éprouver entièrement hors d'Android — et ce parcours est
 * précisément la partie où une erreur de rang passerait inaperçue à l'œil.
 */
class VisiteGuidee(val exposition: Exposition) {

    val étapes: List<Étape> = exposition.oeuvres.flatMapIndexed { rang, oeuvre ->
        // La vue d'ensemble D'ABORD, toujours : entrer dans une toile par un
        // détail agrandi, sans l'avoir vue entière, ne veut rien dire. On
        // montre l'œuvre, puis on entre dedans.
        listOf<Étape>(Étape.VueDEnsemble(rang, oeuvre)) +
            oeuvre.points.map { Étape.Detail(rang, oeuvre, it) }
    }

    val taille: Int get() = étapes.size

    fun étape(rang: Int): Étape? = étapes.getOrNull(rang)

    /**
     * Le rang suivant, borné aux extrémités.
     *
     * Pas de rebouclage, comme pour le fil d'information : arriver au bout et
     * retomber au début donne l'impression d'une liste sans fin, où l'on ne
     * sait plus si l'on a tout vu. Le bouton se grise, et c'est clair.
     */
    fun suivant(rang: Int): Int = (rang + 1).coerceIn(0, (taille - 1).coerceAtLeast(0))

    fun précédent(rang: Int): Int = (rang - 1).coerceIn(0, (taille - 1).coerceAtLeast(0))
}
