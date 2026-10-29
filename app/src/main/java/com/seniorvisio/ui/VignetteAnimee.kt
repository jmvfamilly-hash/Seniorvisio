package com.seniorvisio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.seniorvisio.core.CallTrace

/**
 * Petite vignette animée (écureuil, mouette, etc.) qui joue par-dessus
 * l'écran d'accueil une fois par heure, un agrément visuel discret et non
 * interactif — voir MainActivity, appliquerVignetteHoraire.
 *
 * ═══ POURQUOI UNE SUITE D'IMAGES PLUTÔT QU'UNE VIDÉO ═══
 *
 * Voir scripts/traiter_vignette.sh : Android ne sait pas retirer un fond
 * vert d'une vidéo en temps réel de façon fiable sur toutes les tablettes,
 * et minSdk 26 exclut le support natif du WebP animé (API 28+). Le
 * détourage est donc fait une fois pour toutes hors tablette ; cette vue
 * ne fait que dérouler les images obtenues, une seule à la fois en
 * mémoire — la tablette de test tourne avec un tas Java d'à peine 192 Mo.
 */
class VignetteAnimee @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmapCourant: Bitmap? = null
    private var dossierActuel: String? = null
    private var imageActuelle = 0
    private var nombreImages = 0
    private var délaiMs = 100L
    private var onTermineActuel: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tic = Runnable { afficherImageSuivante() }

    /** Démarre la scène [dossier] (voir assets/animations/) ; ignoré si une vignette joue déjà. */
    fun jouer(dossier: String, nombreImages: Int, imagesParSeconde: Int, onTermine: () -> Unit) {
        if (dossierActuel != null) return
        dossierActuel = dossier
        this.nombreImages = nombreImages
        délaiMs = 1_000L / imagesParSeconde
        imageActuelle = 0
        onTermineActuel = onTermine
        visibility = VISIBLE
        CallTrace.record("ACCUEIL vignette", "$dossier démarrée")
        handler.post(tic)
    }

    private fun afficherImageSuivante() {
        val dossier = dossierActuel ?: return
        imageActuelle++
        if (imageActuelle > nombreImages) {
            terminer()
            return
        }
        val bitmap = try {
            context.assets.open("animations/$dossier/${"frame_%03d".format(imageActuelle)}.webp").use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            CallTrace.record("ACCUEIL vignette ÉCHEC", "$dossier image $imageActuelle : ${e.message}")
            terminer()
            return
        }
        bitmapCourant?.recycle()
        bitmapCourant = bitmap
        invalidate()
        handler.postDelayed(tic, délaiMs)
    }

    private fun terminer() {
        val dossier = dossierActuel
        dossierActuel = null
        visibility = GONE
        bitmapCourant?.recycle()
        bitmapCourant = null
        if (dossier != null) CallTrace.record("ACCUEIL vignette", "$dossier terminée")
        val rappel = onTermineActuel
        onTermineActuel = null
        rappel?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmapCourant ?: return
        // Un quart de la hauteur de l'écran (voir le gabarit de prompt
        // Gemini), posée dans la bande basse, centrée horizontalement.
        val hauteurCible = height * 0.25f
        val échelle = hauteurCible / bmp.height
        val largeurCible = bmp.width * échelle
        val left = (width - largeurCible) / 2f
        val top = height * 0.65f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + largeurCible, top + hauteurCible), null)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tic)
        dossierActuel = null
        bitmapCourant?.recycle()
        bitmapCourant = null
        super.onDetachedFromWindow()
    }
}
