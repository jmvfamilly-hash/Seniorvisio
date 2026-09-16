package com.seniorvisio.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Le glissement du doigt pour avancer ou reculer — et rien d'autre.
 *
 * ═══ UN SECOND CHEMIN, JAMAIS LE SEUL ═══
 *
 * Les consignes d'accessibilité retenues pour ce projet tolèrent le swipe
 * « pour des interactions très secondaires et naturelles, comme faire défiler
 * une galerie de photos », à une condition ferme : il ne doit JAMAIS dissimuler
 * une fonction essentielle.
 *
 * C'est exactement le rôle qu'il tient ici. Tout ce que ce geste fait, deux
 * boutons visibles le font aussi, au même endroit, au même moment. Quelqu'un
 * qui ne découvrirait jamais le glissement ne perdrait strictement rien. Et
 * personne n'a à l'apprendre : il se trouve tout seul, ou pas du tout.
 *
 * Aucune autre fonction ne doit venir se greffer ici. Le jour où un geste ferait
 * quelque chose qu'aucun bouton ne fait, cette classe serait devenue le défaut
 * qu'elle documente.
 *
 * ═══ POURQUOI CE SEUIL, ET PAS CELUI D'ANDROID ═══
 *
 * Le seuil par défaut d'un système suppose une main sûre. Ici, un doigt qui
 * appuie en tremblant produit un petit déplacement involontaire : réglé trop
 * bas, le seuil ferait défiler la photo au moment même où Jean essaie de la
 * regarder — et il n'aurait aucune idée de ce qu'il a fait.
 *
 * On demande donc un geste franc : une distance large, une vitesse minimale, et
 * un mouvement nettement plus horizontal que vertical. Mieux vaut un glissement
 * ignoré — les boutons sont là — qu'un glissement déclenché sans intention.
 */
object GlissementHorizontal {

    /** Un quart de la largeur d'une dalle de dix pouces, en gros : un vrai geste. */
    private const val DISTANCE_MIN_PX = 180f

    /** Écarte les déplacements lents, qui sont des appuis qui ont glissé. */
    private const val VITESSE_MIN_PX_S = 250f

    /**
     * Le geste doit être au moins deux fois plus horizontal que vertical.
     * Sans cette condition, un doigt qui descend en biais sur l'écran
     * changerait de photo.
     */
    private const val RAPPORT_HORIZONTAL = 2f

    /**
     * @param surGlissement reçoit vrai pour un glissement vers la GAUCHE — le
     *   contenu avance, comme on tourne une page — et faux vers la droite.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun brancher(vue: View, surGlissement: (Boolean) -> Unit) {
        val détecteur = GestureDetector(vue.context, object : GestureDetector.SimpleOnGestureListener() {
            // Obligatoire : sans retourner vrai ici, le détecteur considère que
            // le geste ne l'intéresse pas et ne verra jamais le onFling.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                départ: MotionEvent?,
                arrivée: MotionEvent,
                vitesseX: Float,
                vitesseY: Float,
            ): Boolean {
                val début = départ ?: return false
                val dx = arrivée.x - début.x
                val dy = arrivée.y - début.y
                if (abs(dx) < DISTANCE_MIN_PX) return false
                if (abs(vitesseX) < VITESSE_MIN_PX_S) return false
                if (abs(dx) < abs(dy) * RAPPORT_HORIZONTAL) return false
                surGlissement(dx < 0)
                return true
            }
        })
        vue.setOnTouchListener { _, événement -> détecteur.onTouchEvent(événement) }
    }
}
