package com.seniorvisio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Horloge à aiguilles plein écran, lisible à trois mètres, qui remplace le
 * diaporama de photos sur l'écran d'accueil de Jean.
 *
 * ═══ POURQUOI UNE VUE DESSINÉE À LA MAIN ═══
 *
 * Un dessin vectoriel (chiffres + aiguilles) reste net à n'importe quelle
 * taille de dalle, là où une image bitmap se serait floutée en grand format.
 * Les aiguilles tournent en code à chaque seconde, ce qu'un simple drawable
 * statique ne sait pas faire.
 *
 * ═══ ELLE SUIT LA PALETTE, PAS UNE COULEUR FIXE ═══
 *
 * setCouleur est appelé depuis le même rappel que les autres couleurs de
 * l'écran (voir MainActivity, onPalette de HomeZonesController) : cadran
 * clair le jour, cadran sombre la nuit, comme le reste de l'écran.
 */
class HorlogeAiguilles @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var couleur = Color.parseColor("#101A2E")

    // Couleur d'accent de l'aiguille des heures et du moyeu central,
    // distincte du reste du cadran (chiffres, graduations, aiguille des
    // minutes) — demandé pour égayer un écran jugé trop austère une fois
    // tout posé dans la même teinte uniforme. Même défaut que couleur tant
    // que setCouleurAccent n'a pas encore été appelé.
    private var couleurAccent = couleur

    private val traitCadran = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        color = couleur
    }
    private val traitGraduation = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = couleur
    }
    private val traitAiguilleMinutes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = couleur
    }
    private val traitAiguilleHeures = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = couleurAccent
    }
    private val fondCentre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = couleurAccent
    }
    private val texteChiffres = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = couleur
    }

    /** Palette du moment (voir ScreenTheme) : même teinte que la date et la météo. */
    fun setCouleur(nouvelle: Int) {
        couleur = nouvelle
        traitCadran.color = nouvelle
        traitGraduation.color = nouvelle
        traitAiguilleMinutes.color = nouvelle
        texteChiffres.color = nouvelle
        invalidate()
    }

    /**
     * Couleur de l'aiguille des heures et du moyeu, à part du reste —
     * voir MainActivity, qui la choisit selon jour/nuit (même logique que
     * l'icône du moment de la journée dans la maquette d'origine).
     */
    fun setCouleurAccent(nouvelle: Int) {
        couleurAccent = nouvelle
        traitAiguilleHeures.color = nouvelle
        fondCentre.color = nouvelle
        invalidate()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1_000L)
        }
    }

    // Repris à chaque dessin (voir onDraw), pour que
    // positionAuDessusDuSix n'ait pas à refaire le calcul du cadran — juste
    // à lire où il en est.
    private var dernierCentreX = 0f
    private var dernierCentreY = 0f
    private var dernierRayon = 0f

    /**
     * Où poser une étiquette fixe à l'intérieur du cadran, au-dessus du
     * chiffre 6 (posé à 0,68 · rayon, voir onDraw) — une position immobile,
     * demandée après un essai où la suivre à l'opposé des aiguilles (son
     * premier comportement) paraissait trop animée pour ce qu'elle affiche.
     *
     * Coordonnées dans le repère de CETTE vue (0,0 en haut à gauche) : à
     * l'appelant de les décaler par la position de la vue elle-même s'il
     * veut une coordonnée écran (voir MainActivity,
     * repositionnerMomentDansHorloge).
     */
    fun positionAuDessusDuSix(fractionRayon: Float = 0.4f): Pair<Float, Float> {
        return dernierCentreX to (dernierCentreY + dernierRayon * fractionRayon)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tic)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tic)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centreX = width / 2f
        val centreY = height / 2f
        // 0,873 = 0,97 réduit de 10 %, demandé après un premier essai sur la
        // tablette — le cadran occupait encore plus de place que prévu une
        // fois le bandeau réduit à une ligne.
        val rayon = min(width, height) / 2f * 0.873f
        if (rayon <= 0f) return
        dernierCentreX = centreX
        dernierCentreY = centreY
        dernierRayon = rayon

        canvas.drawCircle(centreX, centreY, rayon, traitCadran)

        // Douze graduations, plus épaisses et plus longues aux heures
        // cardinales (12, 3, 6, 9) pour qu'elles se repèrent d'un coup d'œil,
        // même à distance.
        for (heure in 0 until 12) {
            val angle = Math.toRadians((heure * 30 - 90).toDouble())
            val cardinale = heure % 3 == 0
            traitGraduation.strokeWidth = (if (cardinale) 6f else 3f) * resources.displayMetrics.density
            val rExt = rayon * 0.98f
            val rInt = rayon * (if (cardinale) 0.88f else 0.92f)
            canvas.drawLine(
                centreX + (rInt * cos(angle)).toFloat(),
                centreY + (rInt * sin(angle)).toFloat(),
                centreX + (rExt * cos(angle)).toFloat(),
                centreY + (rExt * sin(angle)).toFloat(),
                traitGraduation,
            )
        }

        // Chiffres 12/3/6/9 seulement : assez pour se repérer, sans
        // surcharger un cadran déjà lisible par ses graduations.
        texteChiffres.textSize = rayon * 0.22f
        val rChiffres = rayon * 0.68f
        val métriques = texteChiffres.fontMetrics
        val décalageVertical = -(métriques.ascent + métriques.descent) / 2f
        listOf(0 to "12", 3 to "3", 6 to "6", 9 to "9").forEach { (heure, libellé) ->
            val angle = Math.toRadians((heure * 30 - 90).toDouble())
            canvas.drawText(
                libellé,
                centreX + (rChiffres * cos(angle)).toFloat(),
                centreY + (rChiffres * sin(angle)).toFloat() + décalageVertical,
                texteChiffres,
            )
        }

        val maintenant = Calendar.getInstance()
        val heures = maintenant.get(Calendar.HOUR)
        val minutes = maintenant.get(Calendar.MINUTE)
        val degHeure = (heures + minutes / 60f) * 30f
        val degMinute = minutes * 6f

        // Épaissies (8dp/5dp -> 14dp/10dp) : signalées trop fines à l'essai.
        // L'aiguille des heures porte la couleur d'accent, celle des minutes
        // reste dans la teinte d'encre du reste du cadran.
        traitAiguilleHeures.strokeWidth = 14f * resources.displayMetrics.density
        dessinerAiguille(canvas, centreX, centreY, degHeure, rayon * 0.52f, traitAiguilleHeures)
        traitAiguilleMinutes.strokeWidth = 10f * resources.displayMetrics.density
        dessinerAiguille(canvas, centreX, centreY, degMinute, rayon * 0.78f, traitAiguilleMinutes)

        canvas.drawCircle(centreX, centreY, 6f * resources.displayMetrics.density, fondCentre)
    }

    private fun dessinerAiguille(canvas: Canvas, centreX: Float, centreY: Float, degrés: Float, longueur: Float, trait: Paint) {
        val angle = Math.toRadians((degrés - 90).toDouble())
        canvas.drawLine(
            centreX, centreY,
            centreX + (longueur * cos(angle)).toFloat(),
            centreY + (longueur * sin(angle)).toFloat(),
            trait,
        )
    }
}
