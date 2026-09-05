package com.seniorvisio.ui

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * Une zone de texte de l'écran de Jean (les paroles de la pièce, ou celles de
 * l'appel — voir HomeZonesController), avec la règle d'affichage commune aux
 * deux : cadencé, et jamais rien qui disparaisse avant d'avoir pu être lu.
 *
 * Le problème que ça résout : la transcription arrive au rythme de la parole,
 * qui est bien plus rapide que la lecture — a fortiori pour Jean. Afficher
 * chaque phrase dès son arrivée revient à effacer la précédente avant qu'elle
 * n'ait été lue ; c'est exactement ce que faisait le bandeau d'appel, d'où le
 * "retard de lecture" qu'il fallait signaler au proche pour qu'il ralentisse
 * de lui-même. Ici, c'est l'affichage qui s'adapte : chaque phrase reste à
 * l'écran le temps nécessaire à sa lecture, les suivantes attendent leur tour
 * dans une file, et la zone ne s'efface que lorsque la file est vide.
 *
 * Le corollaire, assumé : la personne qui parle vite prend de l'avance sur ce
 * que Jean lit. Cet écart est mesuré en continu ([pendingSeconds]) et remonté
 * au proche côté PWA, où il sert à la fois d'indicateur pour temporiser et à
 * afficher exactement ce que Jean a sous les yeux, au même instant.
 *
 * Une phrase trop longue pour la hauteur visible n'est pas tronquée : elle
 * défile à vitesse plafonnée (voir CaptionScrollAnimator), et sa durée
 * d'affichage est prolongée d'autant.
 */
class PacedCaptionZone(
    private val container: View,
    private val scrollView: ScrollView,
    private val textView: TextView,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Phrases terminées en attente de leur tour. */
    private val queue = ArrayDeque<String>()

    /** Texte actuellement à l'écran, null si la zone est vide. */
    private var displayed: String? = null

    /** Instant à partir duquel le texte affiché est considéré comme lu. */
    private var readableAtMs = 0L

    /** Vrai tant que la phrase à l'écran est celle en cours d'énonciation (elle continue de s'allonger). */
    private var displayedIsLive = false

    /** Dernier allongement de la phrase en cours (voir advance, garde-fou de fin de tour). */
    private var lastLiveUpdateAtMs = 0L

    private var maxScrollSpeedPxPerSec = 50f * scrollView.resources.displayMetrics.density
    private val scrollAnimator = CaptionScrollAnimator(
        scrollView = scrollView,
        maxSpeedPxPerSec = { maxScrollSpeedPxPerSec },
    )

    private val ticker = object : Runnable {
        override fun run() {
            advance()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        // Le défilement est piloté par le code, jamais par un doigt sur
        // l'écran : Jean n'a rien à manipuler, et un défilement accidentel
        // ferait disparaître du texte sans moyen de le retrouver.
        scrollView.setOnTouchListener { _, _ -> true }
        container.alpha = 0f
        container.visibility = View.GONE
        handler.post(ticker)
    }

    /**
     * Nouveau morceau de transcription. `isFinal` distingue une phrase close
     * d'un tour de parole encore en cours, dont le texte va continuer de
     * s'allonger mot à mot.
     *
     * Un tour en cours s'affiche directement quand la zone est libre : sans
     * ça, rien n'apparaîtrait tant que la personne n'a pas fini sa phrase, ce
     * qui donne l'impression que la transcription ne marche pas. S'il y a
     * déjà quelque chose à lire à l'écran, en revanche, on ne le remplace
     * pas — le tour en cours reviendra de toute façon en version close.
     */
    fun submit(text: String, isFinal: Boolean) {
        val phrase = text.trim()
        if (phrase.isEmpty()) return

        if (!isFinal) {
            if (queue.isNotEmpty()) return
            if (displayed != null && !displayedIsLive) return
            show(phrase, live = true)
            return
        }

        // Une phrase close est toujours la version définitive du tour de
        // parole en cours d'affichage : elle le remplace sur place plutôt que
        // de s'ajouter derrière lui. C'est aussi ce qui met fin à l'état
        // "en cours" — sans quoi la phrase affichée n'expirerait jamais (voir
        // advance) et la file ne se viderait plus.
        if (displayedIsLive || displayed == null) show(phrase, live = false) else queue.addLast(phrase)
    }

    /** Vide la zone immédiatement (fin d'appel, sortie d'écran). */
    fun clear() {
        queue.clear()
        displayed = null
        displayedIsLive = false
        readableAtMs = 0L
        textView.text = ""
        scrollAnimator.jumpTo(0)
        hide()
    }

    /**
     * Ce que Jean a réellement sous les yeux à cet instant, null si la zone
     * est vide — et non la dernière transcription reçue, qui a presque
     * toujours de l'avance. C'est cette valeur que le PWA rejoue pour montrer
     * au proche la même chose au même moment (voir IncomingCallActivity).
     */
    fun displayedText(): String? = displayed

    /** À appeler quand l'écran qui héberge cette zone disparaît, pour ne pas laisser tourner le tic. */
    fun release() {
        handler.removeCallbacks(ticker)
    }

    /**
     * Combien de secondes de lecture Jean a encore devant lui : le temps
     * restant sur la phrase affichée, plus celui de toutes les phrases en
     * attente. C'est la mesure de l'avance prise par la personne qui parle —
     * remontée au proche pour qu'il sache s'il doit temporiser un peu ou
     * beaucoup (voir web-caller/app.js).
     */
    fun pendingSeconds(): Float {
        val remainingOnScreenMs = (readableAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val queuedMs = queue.sumOf { readingDurationMs(it) }
        return (remainingOnScreenMs + queuedMs) / 1000f
    }

    fun setTextSizeSp(sizeSp: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    fun setScrollSpeedDpPerSec(dpPerSec: Float) {
        maxScrollSpeedPxPerSec = dpPerSec * scrollView.resources.displayMetrics.density
    }

    /**
     * Fond dessiné en code plutôt que par un drawable XML teinté : le coin
     * arrondi doit survivre au changement de palette (voir ScreenTheme), et
     * setBackgroundColor l'aurait remplacé par un rectangle net.
     */
    fun applyColors(textColor: Int, backgroundColor: Int) {
        textView.setTextColor(textColor)
        container.background = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * container.resources.displayMetrics.density
            setColor(backgroundColor)
        }
    }

    /**
     * Passe à la phrase suivante dès que celle à l'écran a eu le temps d'être
     * lue, et efface la zone quand il n'y a plus rien en attente. Un tour de
     * parole encore en cours n'expire jamais : tant que la personne parle, sa
     * phrase continue de s'allonger et doit rester visible.
     */
    private fun advance() {
        if (displayed == null) return
        val now = System.currentTimeMillis()
        if (displayedIsLive) {
            // Un tour de parole n'est "en cours" que tant qu'il s'allonge
            // vraiment. Sans ce garde-fou, une connexion AssemblyAI perdue en
            // plein milieu d'une phrase (elle n'enverra alors jamais sa
            // version close) laisserait ce bout de phrase figé à l'écran pour
            // le reste de l'appel.
            if (now - lastLiveUpdateAtMs < LIVE_STALE_MS) return
            displayedIsLive = false
            readableAtMs = now + readingDurationMs(displayed!!)
            return
        }
        if (now < readableAtMs) return
        val next = queue.removeFirstOrNull()
        if (next != null) show(next, live = false) else clear()
    }

    private fun show(phrase: String, live: Boolean) {
        displayed = phrase
        displayedIsLive = live
        if (live) lastLiveUpdateAtMs = System.currentTimeMillis()
        textView.text = phrase
        reveal()
        // Posée tout de suite, avant même de savoir si le texte débordera :
        // la mesure ci-dessous n'a lieu qu'au prochain passage de mise en
        // page, et d'ici là advance() pourrait voir une échéance périmée et
        // enchaîner sur la phrase suivante sans laisser le temps de lire
        // celle-ci.
        readableAtMs = System.currentTimeMillis() + readingDurationMs(phrase)

        textView.post {
            // Une phrase plus haute que la zone défile jusqu'en bas au lieu
            // d'être coupée, et le temps que prend ce défilement s'ajoute à
            // sa durée d'affichage : sans ça, la phrase suivante s'affichait
            // avant même que la fin de la précédente ne soit apparue.
            val overflowPx = (textView.height - scrollView.height).coerceAtLeast(0)
            scrollAnimator.jumpTo(0)
            var durationMs = readingDurationMs(phrase)
            if (overflowPx > 0) {
                scrollAnimator.scrollTo(overflowPx)
                if (maxScrollSpeedPxPerSec > 0f) {
                    durationMs += (overflowPx / maxScrollSpeedPxPerSec * 1000f).toLong()
                }
            }
            readableAtMs = System.currentTimeMillis() + durationMs
        }
    }

    /**
     * Temps de lecture d'une phrase, à une vitesse volontairement lente :
     * 130 mots par minute, contre 200 à 250 pour un lecteur adulte pressé.
     * Le plancher compte autant que la vitesse — même un seul mot doit rester
     * assez longtemps pour être remarqué, lu, et compris.
     */
    private fun readingDurationMs(phrase: String): Long {
        val words = phrase.split(' ').count { it.isNotBlank() }
        val readingMs = words * 60_000L / WORDS_PER_MINUTE
        return (NOTICE_DELAY_MS + readingMs).coerceAtLeast(MIN_DISPLAY_MS)
    }

    private fun reveal() {
        if (container.visibility == View.VISIBLE && container.alpha == 1f) return
        container.animate().cancel()
        container.visibility = View.VISIBLE
        container.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun hide() {
        if (container.visibility == View.GONE) return
        container.animate().cancel()
        container.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { container.visibility = View.GONE }
            .start()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val FADE_MS = 400L
        private const val CORNER_RADIUS_DP = 16f
        private const val WORDS_PER_MINUTE = 130L

        /** Temps de simple prise de conscience qu'un texte vient d'apparaître, avant même de le lire. */
        private const val NOTICE_DELAY_MS = 1_200L
        private const val MIN_DISPLAY_MS = 2_500L

        /**
         * Au-delà de ce silence, la phrase en cours est considérée comme
         * terminée même si sa version close n'est jamais arrivée. Plus long
         * qu'une simple respiration, assez court pour ne pas laisser un bout
         * de phrase figé à l'écran après une coupure de connexion.
         */
        private const val LIVE_STALE_MS = 6_000L
    }
}
