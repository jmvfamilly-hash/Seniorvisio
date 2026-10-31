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
import com.seniorvisio.vignette.VignetteStore
import java.io.File

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
 * détourage est donc fait une fois pour toutes hors tablette ; cette vue ne
 * fait que dérouler les images obtenues, une seule à la fois en mémoire —
 * la tablette de test tourne avec un tas Java d'à peine 192 Mo.
 *
 * ═══ LES IMAGES VIENNENT DU DISQUE, PAS DE L'APK ═══
 *
 * Voir VignetteStore : une scène est téléchargée et vérifiée par la
 * tablette elle-même, comme les photos des recueils, plutôt
 * qu'embarquée dans l'APK. Deux raisons : ajouter une scène ne demande
 * alors ni recompilation ni réinstallation, et la définition de traitement
 * n'est plus bridée par le poids de l'APK — elle peut suivre la taille
 * réelle d'affichage (voir scripts/traiter_vignette.sh).
 */
class VignetteAnimee @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmapCourant: Bitmap? = null
    private var dossierActuel: File? = null
    private var nomScèneActuelle: String? = null
    private var imageActuelle = 0
    private var nombreImages = 0
    private var délaiMs = 100L
    private var onTermineActuel: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tic = Runnable { afficherImageSuivante() }

    /**
     * Démarre la scène [nomScène] (voir VignetteStore.scenesInstallées) ;
     * ignoré si une vignette joue déjà. N'échoue jamais bruyamment : une
     * scène pas encore téléchargée, ou disparue entre-temps, passe
     * simplement son tour — voir démarrerEnchaînementVignettes côté
     * MainActivity, qui enchaîne sur la suivante sans attendre.
     */
    fun jouer(nomScène: String, onTermine: () -> Unit = {}) {
        if (dossierActuel != null) return
        val magasin = VignetteStore.actif
        val dossier = magasin?.dossierDe(nomScène)
        val compte = dossier?.listFiles { f -> f.name.startsWith("frame_") }?.size ?: 0
        if (dossier == null || compte <= 0) {
            CallTrace.record("ACCUEIL vignette ÉCHEC", "$nomScène : pas encore installée sur la tablette")
            onTermine()
            return
        }
        dossierActuel = dossier
        nomScèneActuelle = nomScène
        nombreImages = compte
        délaiMs = 1_000L / (magasin.imagesParSecondeDe(nomScène).coerceAtLeast(1))
        imageActuelle = 0
        onTermineActuel = onTermine
        visibility = VISIBLE
        CallTrace.record("ACCUEIL vignette", "$nomScène démarrée ($compte images)")
        handler.post(tic)
    }

    private fun afficherImageSuivante() {
        val dossier = dossierActuel ?: return
        imageActuelle++
        if (imageActuelle > nombreImages) {
            terminer()
            return
        }
        val fichier = File(dossier, "${"frame_%03d".format(imageActuelle)}.webp")
        val bitmap = try {
            fichier.inputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            CallTrace.record("ACCUEIL vignette ÉCHEC", "$nomScèneActuelle image $imageActuelle : ${e.message}")
            terminer()
            return
        }
        bitmapCourant?.recycle()
        bitmapCourant = bitmap
        invalidate()
        handler.postDelayed(tic, délaiMs)
    }

    private fun terminer() {
        val nomScène = nomScèneActuelle
        dossierActuel = null
        nomScèneActuelle = null
        visibility = GONE
        bitmapCourant?.recycle()
        bitmapCourant = null
        if (nomScène != null) CallTrace.record("ACCUEIL vignette", "$nomScène terminée")
        val rappel = onTermineActuel
        onTermineActuel = null
        rappel?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmapCourant ?: return
        // Grande part de l'écran (demandé après un premier essai jugé trop
        // discret à 25 % de la hauteur) : quasi toute la hauteur disponible,
        // centrée, en conservant les proportions de l'image.
        val hauteurCible = height * 0.92f
        val échelle = hauteurCible / bmp.height
        val largeurCible = bmp.width * échelle
        val left = (width - largeurCible) / 2f
        val top = (height - hauteurCible) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + largeurCible, top + hauteurCible), null)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tic)
        dossierActuel = null
        nomScèneActuelle = null
        bitmapCourant?.recycle()
        bitmapCourant = null
        super.onDetachedFromWindow()
    }
}
