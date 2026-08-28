package com.seniorvisio.ui

import android.view.Choreographer
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fait défiler un ScrollView de sous-titres vers une position cible à une
 * vitesse plafonnée et constante, plutôt qu'un saut instantané ou une durée
 * fixe (qui accélérait d'autant plus que le texte en attente était long —
 * l'inverse de ce qu'il faut pour laisser le temps de lire, constaté lors
 * des tests réels). Suivi continu par interpolation image par image
 * (Choreographer), validé dans le labo de défilement
 * (experiment/caption-scroll) sur un enregistrement vocal réel — 60 im/s en
 * moyenne, quasi aucune image saccadée.
 *
 * Logique commune aux sous-titres d'appel (IncomingCallActivity) et de la
 * pièce (MainActivity), factorisée ici pour ne pas la dupliquer.
 */
class CaptionScrollAnimator(
    private val scrollView: ScrollView,
    private val maxSpeedPxPerSec: () -> Float,
    /** Appelé à chaque image avec la distance restante (px) jusqu'à la cible. */
    private val onFrame: ((remainingPx: Int) -> Unit)? = null,
) {
    private var target = 0
    private var frameScheduled = false
    private var lastFrameTimeNanos = 0L

    /** Fait défiler progressivement jusqu'à [targetY], à vitesse plafonnée. */
    fun scrollTo(targetY: Int) {
        target = targetY
        requestFrame()
    }

    /** Repositionne immédiatement, sans animation (ex. nouvelle phrase). */
    fun jumpTo(targetY: Int) {
        target = targetY
        scrollView.scrollTo(0, targetY)
        lastFrameTimeNanos = 0L
    }

    private fun requestFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            frameScheduled = false
            val dtSeconds = if (lastFrameTimeNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
            lastFrameTimeNanos = frameTimeNanos
            val current = scrollView.scrollY
            val diff = (target - current).toFloat()
            if (abs(diff) < 1f) {
                scrollView.scrollTo(0, target)
            } else {
                val maxStep = (maxSpeedPxPerSec() * dtSeconds).coerceAtLeast(1f)
                val step = diff.coerceIn(-maxStep, maxStep)
                scrollView.scrollTo(0, (current + step).roundToInt())
                requestFrame()
            }
            onFrame?.invoke((target - scrollView.scrollY).coerceAtLeast(0))
        }
    }
}
