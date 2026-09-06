package com.seniorvisio.ui

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * Une zone de texte de l'écran de Jean — les paroles de la pièce, ou celles de
 * l'appel (voir HomeZonesController). Les deux obéissent exactement à la même
 * règle d'affichage, celle du bandeau de sous-titres d'origine, éprouvée en
 * usage réel : défilement continu façon sous-titrage TV en direct ("roll-up",
 * CEA-608).
 *
 * Tant que le texte reçu prolonge le précédent (la personne continue sa
 * phrase, un mot de plus toutes les ~500 ms), le défilement avance d'un cran
 * sans revenir en haut. Repartir de zéro à chaque mise à jour rendait le
 * défilement inutilisable en parole continue : l'animation n'avait jamais le
 * temps d'aller au bout avant d'être relancée depuis le début. On ne remonte
 * qu'au démarrage d'une phrase réellement nouvelle.
 *
 * Le défilement est plafonné à une vitesse constante et réglable à distance
 * (voir setScrollSpeedDpPerSec) plutôt que proportionnel au texte en attente :
 * l'ancien réglage accélérait d'autant plus que la personne parlait vite,
 * exactement l'inverse de ce qu'il faut pour laisser à Jean le temps de lire.
 * Le retard qui en résulte est mesuré ([pendingSeconds]) et remonté à
 * l'appelant, pour qu'il sache où en est Jean plutôt que d'avoir à le deviner.
 *
 * La zone s'efface — texte ET cadre — après un délai sans nouvelle parole. Ne
 * masquer que le texte ne suffit pas : un cadre semi-opaque vide reste alors
 * plaqué à l'écran, ce qui gêne particulièrement par-dessus une photo de
 * diaporama.
 */
class RollingCaptionZone(
    private val container: View,
    private val scrollView: ScrollView,
    private val textView: TextView,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Texte actuellement à l'écran, null si la zone est vide. */
    private var displayed: String? = null

    private var visibleLines = DEFAULT_VISIBLE_LINES
    private var clearDelayMs = DEFAULT_CLEAR_DELAY_MS
    private var maxScrollSpeedPxPerSec = DEFAULT_SCROLL_SPEED_DP_PER_SEC * scrollView.resources.displayMetrics.density

    private val scrollAnimator = CaptionScrollAnimator(
        scrollView = scrollView,
        maxSpeedPxPerSec = { maxScrollSpeedPxPerSec },
    )

    private val clearRunnable = Runnable { clear() }

    init {
        // Le défilement est piloté par le code, jamais par un doigt sur
        // l'écran : Jean n'a rien à manipuler, et un défilement accidentel
        // ferait disparaître du texte sans moyen de le retrouver.
        scrollView.setOnTouchListener { _, _ -> true }
        container.alpha = 0f
        container.visibility = View.INVISIBLE
        // La taille de police se déduit de la hauteur réelle de la zone, qui
        // n'est connue qu'une fois la mise en page faite — et qui change à
        // chaque rotation.
        scrollView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) fitTextToVisibleLines()
        }
    }

    /**
     * Nouveau texte transcrit. `isFinal` n'est pas utilisé ici : contrairement
     * à un affichage phrase par phrase, le défilement continu se moque de
     * savoir si la phrase est close — il suit la parole telle qu'elle arrive,
     * révisions comprises. Le paramètre reste dans la signature parce que les
     * deux sources en disposent et qu'un futur affichage pourrait s'en servir.
     */
    fun submit(text: String, @Suppress("UNUSED_PARAMETER") isFinal: Boolean) {
        val phrase = text.trim()
        if (phrase.isEmpty()) return

        val isContinuation = displayed?.let { phrase.startsWith(it) } == true
        displayed = phrase
        textView.alpha = 1f
        textView.text = phrase
        reveal()

        handler.removeCallbacks(clearRunnable)
        handler.postDelayed(clearRunnable, clearDelayMs)

        textView.post {
            // Une phrase réellement nouvelle repart du haut ; la suite d'une
            // phrase en cours poursuit son défilement là où il en était.
            if (!isContinuation) scrollAnimator.jumpTo(0)
            val maxScroll = (textView.height - scrollView.height).coerceAtLeast(0)
            if (maxScroll > 0) scrollAnimator.scrollTo(maxScroll)
        }
    }

    /** Vide la zone immédiatement (fin d'appel, sortie d'écran, silence prolongé). */
    fun clear() {
        handler.removeCallbacks(clearRunnable)
        if (displayed == null) return
        displayed = null
        textView.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            textView.text = ""
            textView.alpha = 1f
            scrollAnimator.jumpTo(0)
        }.start()
        hide()
    }

    /**
     * Ce que Jean a réellement sous les yeux à cet instant, null si la zone
     * est vide. C'est cette valeur que le PWA rejoue pour montrer à l'appelant
     * la même chose au même moment (voir IncomingCallActivity).
     */
    fun displayedText(): String? = displayed

    /** À appeler quand l'écran qui héberge cette zone disparaît. */
    fun release() {
        handler.removeCallbacks(clearRunnable)
    }

    /**
     * Combien de secondes de lecture Jean a encore devant lui : la distance
     * qu'il reste à parcourir en défilement, divisée par la vitesse à laquelle
     * ce défilement avance. Zéro quand il a tout lu.
     */
    fun pendingSeconds(): Float {
        if (maxScrollSpeedPxPerSec <= 0f) return 0f
        val maxScroll = (textView.height - scrollView.height).coerceAtLeast(0)
        val remaining = (maxScroll - scrollView.scrollY).coerceAtLeast(0)
        return remaining / maxScrollSpeedPxPerSec
    }

    /**
     * Nombre de lignes visibles avant que le texte ne se mette à défiler. La
     * zone occupe une part fixe de l'écran (voir view_home_zones.xml) : c'est
     * donc la taille de la police qui s'ajuste pour qu'exactement ce nombre de
     * lignes y tienne — et non l'inverse. Deux conséquences voulues : Jean
     * retrouve toujours chaque zone au même endroit, et un seul réglage
     * commande à la fois la densité et la taille du texte (moins de lignes =
     * texte plus gros), là où deux curseurs séparés se contredisaient.
     */
    fun setVisibleLines(lines: Int) {
        val clamped = lines.coerceIn(MIN_VISIBLE_LINES, MAX_VISIBLE_LINES)
        if (clamped == visibleLines) return
        visibleLines = clamped
        fitTextToVisibleLines()
    }

    fun setScrollSpeedDpPerSec(dpPerSec: Float) {
        maxScrollSpeedPxPerSec = dpPerSec * scrollView.resources.displayMetrics.density
    }

    fun setClearDelaySeconds(seconds: Int) {
        clearDelayMs = (seconds * 1000L).coerceAtLeast(MIN_CLEAR_DELAY_MS)
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
     * Cherche la taille de police pour laquelle exactement `visibleLines`
     * lignes remplissent la hauteur de la zone.
     *
     * En deux passes plutôt qu'un calcul direct : la hauteur d'une ligne n'est
     * pas exactement proportionnelle à la taille demandée (arrondis de la
     * police, interligne propre à la fonte), mais elle l'est assez pour qu'une
     * simple règle de trois à partir d'une mesure réelle tombe juste au pixel
     * près. Une formule fermée devrait, elle, supposer un rapport interligne
     * fixe — faux dès qu'on change de police ou de langue.
     */
    private fun fitTextToVisibleLines() {
        val availableHeight = scrollView.height
        if (availableHeight <= 0) return

        val targetLineHeight = availableHeight.toFloat() / visibleLines
        repeat(2) {
            val currentLineHeight = textView.lineHeight.toFloat()
            if (currentLineHeight <= 0f) return
            val currentSizePx = textView.textSize
            val fittedPx = (currentSizePx * targetLineHeight / currentLineHeight)
                .coerceIn(MIN_TEXT_SIZE_PX, MAX_TEXT_SIZE_PX)
            if (kotlin.math.abs(fittedPx - currentSizePx) < 0.5f) return
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fittedPx)
        }
    }

    private fun reveal() {
        if (container.visibility == View.VISIBLE && container.alpha == 1f) return
        container.animate().cancel()
        container.visibility = View.VISIBLE
        container.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    /**
     * INVISIBLE et jamais GONE : une zone en GONE sort de la mise en page, et
     * les deux autres se partagent alors sa place — la zone d'appel occupait
     * ainsi la moitié basse de l'écran dès que celle de la pièce était vide,
     * au lieu du tiers du bas. Invisible, elle garde sa place : rien ne bouge
     * jamais, et Jean retrouve toujours chaque chose au même endroit.
     */
    private fun hide() {
        if (container.visibility == View.INVISIBLE) return
        container.animate().cancel()
        container.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { container.visibility = View.INVISIBLE }
            .start()
    }

    companion object {
        private const val FADE_MS = 400L
        private const val CORNER_RADIUS_DP = 16f

        /**
         * Valeurs de départ, reprises du bandeau d'origine : deux lignes
         * visibles, et trente secondes sans nouvelle parole avant effacement —
         * assez long pour ne pas effacer entre deux phrases d'une même
         * explication, assez court pour ne pas laisser une phrase orpheline
         * indéfiniment sur une photo de diaporama.
         */
        private const val DEFAULT_VISIBLE_LINES = 2
        private const val DEFAULT_CLEAR_DELAY_MS = 30_000L
        private const val DEFAULT_SCROLL_SPEED_DP_PER_SEC = 50f

        private const val MIN_VISIBLE_LINES = 1
        private const val MAX_VISIBLE_LINES = 4
        private const val MIN_CLEAR_DELAY_MS = 1_000L

        /** Bornes de sécurité : une zone très plate ou très haute ne doit produire ni texte illisible ni texte absurde. */
        private const val MIN_TEXT_SIZE_PX = 18f
        private const val MAX_TEXT_SIZE_PX = 220f
    }
}
