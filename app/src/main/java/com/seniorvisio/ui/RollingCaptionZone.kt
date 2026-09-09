package com.seniorvisio.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
 * Le texte ne repart jamais de zéro : il s'allonge, et le défilement l'amène
 * sous les yeux de Jean. Une phrase nouvelle se pose à la suite de la
 * précédente et n'apparaît qu'une fois le défilement arrivé jusqu'à elle.
 *
 * Une version antérieure devinait la suite d'une phrase en regardant si le
 * texte reçu commençait par celui déjà affiché. C'était vrai d'AssemblyAI,
 * dont le texte est cumulatif sur toute une prise de parole, et faux du
 * moteur embarqué, qui découpe en énoncés courts et repart d'une chaîne vide
 * après chacun. Le premier mot de l'énoncé suivant était alors pris pour une
 * phrase neuve : la zone se vidait et remontait en haut, emportant les deux
 * lignes que Jean était en train de lire. D'où le passage à `isFinal`, que
 * les deux moteurs fournissent et qui dit la même chose chez l'un et chez
 * l'autre : ce segment-ci est clos, le suivant recommence. Plus rien n'est
 * deviné.
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

    /**
     * Les segments déjà clos, mis bout à bout. Ne change plus une fois écrit :
     * c'est le texte acquis, celui que Jean a lu ou qu'il est en train de
     * rattraper.
     */
    private var committed = ""

    /**
     * Le segment en cours de dictée, encore susceptible d'être révisé par le
     * moteur d'un bloc de son à l'autre. Remplacé à chaque mise à jour, puis
     * versé dans [committed] quand le moteur le déclare clos.
     */
    private var pending = ""

    /** Quand du texte est arrivé pour la dernière fois, pour mesurer les silences. */
    private var lastTextAtMs = 0L

    private var visibleLines = DEFAULT_VISIBLE_LINES
    private var clearDelayMs = DEFAULT_CLEAR_DELAY_MS
    private var maxScrollSpeedPxPerSec = DEFAULT_SCROLL_SPEED_DP_PER_SEC * scrollView.resources.displayMetrics.density

    private val scrollAnimator = CaptionScrollAnimator(
        scrollView = scrollView,
        maxSpeedPxPerSec = { maxScrollSpeedPxPerSec },
    )

    /**
     * L'effacement au bout du délai de silence, mais jamais avant que le
     * défilement ait atteint la fin du texte : tant qu'il reste de la distance
     * à parcourir, il reste des mots que Jean n'a pas encore eus sous les yeux,
     * et les effacer reviendrait à les lui retirer avant qu'il ait pu les lire.
     * On repousse alors juste le temps qu'il faut au défilement pour finir.
     */
    private val clearRunnable = object : Runnable {
        override fun run() {
            val remaining = pendingSeconds()
            if (remaining > 0f) {
                handler.postDelayed(this, (remaining * 1000f).toLong().coerceAtLeast(RECHECK_DELAY_MS))
                return
            }
            clear()
        }
    }

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
     * Nouveau texte transcrit. Un texte non final remplace le segment en
     * cours — les moteurs se corrigent au fil des mots ; un texte final le
     * clôt et le verse à la suite de ce qui précède.
     *
     * Un final vide n'est pas un non-événement : il signale la fin d'un
     * segment que le moteur n'a finalement pas su transcrire, et il faut tout
     * de même fermer celui en attente, sinon le segment suivant l'écraserait
     * en croyant le corriger.
     */
    fun submit(text: String, isFinal: Boolean) {
        val phrase = text.trim()
        // Silence pur : rien à afficher, et surtout pas de délai d'effacement
        // à réarmer — sans quoi la zone ne disparaîtrait plus jamais.
        if (phrase.isEmpty() && (!isFinal || pending.isEmpty())) return

        if (phrase.isNotEmpty()) {
            noteSilenceBefore()
            pending = phrase
        }
        lastTextAtMs = SystemClock.elapsedRealtime()
        if (isFinal) {
            committed = join(committed, pending)
            pending = ""
        }

        textView.alpha = 1f
        textView.text = styledText()
        reveal()

        handler.removeCallbacks(clearRunnable)
        handler.postDelayed(clearRunnable, clearDelayMs)

        textView.post {
            // Jamais de retour en haut : le texte ne fait que s'allonger, et
            // c'est au défilement d'amener la suite sous les yeux de Jean.
            trimTextAlreadyScrolledPast()
            val maxScroll = (textView.height - scrollView.height).coerceAtLeast(0)
            if (maxScroll > 0) scrollAnimator.scrollTo(maxScroll)
        }
    }

    /** Vide la zone immédiatement (fin d'appel, sortie d'écran, silence prolongé). */
    fun clear() {
        handler.removeCallbacks(clearRunnable)
        if (committed.isEmpty() && pending.isEmpty()) return
        committed = ""
        pending = ""
        lastTextAtMs = 0L
        textView.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            textView.text = ""
            textView.alpha = 1f
            scrollAnimator.jumpTo(0)
        }.start()
        hide()
    }

    /**
     * Marque le silence qui précède un nouveau segment. Ne se déclenche qu'au
     * premier mot d'un segment : pendant la dictée, les textes se suivent de
     * quelques dizaines de millisecondes et il n'y a évidemment rien à
     * signaler.
     *
     * L'intérêt n'est pas de faire joli : sans repère, deux phrases dites à
     * une minute d'intervalle se lisent comme une seule, et Jean croit à un
     * enchaînement là où il y a eu une pause. Il lit un texte qui remplace
     * l'écoute — le rythme de la parole en fait partie.
     */
    private fun noteSilenceBefore() {
        if (pending.isNotEmpty()) return
        if (committed.isEmpty()) return
        if (lastTextAtMs == 0L) return
        val silenceMs = SystemClock.elapsedRealtime() - lastTextAtMs
        committed = when {
            // Pause franche : la reprise mérite sa propre ligne, sinon elle se
            // colle à la phrase d'avant et le repère ne sert plus à rien.
            silenceMs >= LONG_SILENCE_MS -> "$committed $SILENCE_MARKER\n"
            silenceMs >= SHORT_SILENCE_MS -> "$committed $SILENCE_MARKER"
            else -> return
        }
    }

    private fun renderedText(): String = join(committed, pending)

    /**
     * Le texte tel qu'il s'affiche : les marques de silence en plus petit et
     * en italique, pour qu'elles se lisent comme une indication et non comme
     * un mot prononcé. Les styles sont recalculés à chaque rendu plutôt que
     * portés par le tampon, qui reste ainsi du texte simple — c'est ce qui
     * permet à la purge des lignes déjà lues de couper au caractère près (voir
     * trimTextAlreadyScrolledPast) et au miroir du PWA de recevoir la même
     * chose sans avoir à comprendre nos styles.
     */
    private fun styledText(): CharSequence {
        val full = renderedText()
        if (!full.contains(SILENCE_MARKER)) return full

        val styled = SpannableStringBuilder(full)
        var from = full.indexOf(SILENCE_MARKER)
        while (from >= 0) {
            val to = from + SILENCE_MARKER.length
            styled.setSpan(RelativeSizeSpan(SILENCE_TEXT_SCALE), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.ITALIC), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = full.indexOf(SILENCE_MARKER, to)
        }
        return styled
    }

    /**
     * Les segments se suivent séparés d'une simple espace, et c'est le retour
     * à la ligne naturel du texte qui les répartit sur les lignes. Un saut de
     * ligne forcé par segment donnerait, avec un moteur qui découpe à chaque
     * respiration, une ligne de trois mots suivie de beaucoup de vide — là où
     * deux lignes bien remplies se lisent d'un coup d'œil.
     */
    private fun join(head: String, tail: String): String = when {
        head.isEmpty() -> tail
        tail.isEmpty() -> head
        // Après une marque de silence longue, la reprise commence déjà une
        // nouvelle ligne : y ajouter une espace la décalerait d'un cran vers
        // la droite, comme un alinéa involontaire.
        head.endsWith("\n") -> head + tail
        else -> "$head $tail"
    }

    /**
     * Ce que Jean a réellement sous les yeux à cet instant, null si la zone
     * est vide. C'est cette valeur que le PWA rejoue pour montrer à l'appelant
     * la même chose au même moment (voir IncomingCallActivity).
     */
    fun displayedText(): String? = renderedText().ifEmpty { null }

    /**
     * Combien de caractères tiennent sur une ligne de cette zone, à la taille
     * de police effectivement appliquée.
     *
     * Mesuré et non calculé : la taille de police découle elle-même de la
     * hauteur réelle de la zone (voir fitTextToVisibleLines), et la largeur
     * moyenne d'un caractère dépend de la fonte. Cette valeur est publiée vers
     * le PWA pour qu'il puisse afficher au proche un pavé de texte qui coupe
     * ses lignes aux mêmes endroits que chez Jean — sans quoi la « même chose
     * au même moment » serait vraie du contenu mais fausse de la forme, et le
     * proche ne saurait pas ce que Jean a réellement sous les yeux.
     */
    fun charsPerLine(): Int {
        val width = scrollView.width - textView.paddingLeft - textView.paddingRight
        if (width <= 0) return 0
        // Un échantillon de lettres courantes plutôt qu'un seul caractère : en
        // fonte proportionnelle, un « i » et un « m » n'ont rien à voir, et
        // mesurer l'un ou l'autre donnerait le double ou la moitié.
        val sample = "abcdefghijklmnopqrstuvwxyz eaisnrtolu"
        val average = textView.paint.measureText(sample) / sample.length
        if (average <= 0f) return 0
        return (width / average).toInt().coerceAtLeast(1)
    }

    /** Le nombre de lignes visibles effectivement appliqué (voir setVisibleLines). */
    fun visibleLines(): Int = visibleLines

    /** Vrai tant que quelque chose est affiché — donc tant qu'il reste à lire. */
    fun hasText(): Boolean = committed.isNotEmpty() || pending.isNotEmpty()

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

    /**
     * Retire du tampon les lignes déjà sorties par le haut. Sans ça, une
     * conversation d'une heure finirait par tenir des milliers de mots dans
     * une zone qui en montre deux lignes, et la distance de défilement
     * grandirait sans fin.
     *
     * Seules les lignes entièrement passées au-dessus de la fenêtre sont
     * jetées : jamais un mot que Jean n'a pas eu sous les yeux — c'est
     * précisément ce qu'on cherche à ne plus faire. La coupe tombe sur un
     * début de ligne, ce qui laisse les retours à la ligne suivants
     * inchangés, et la position comme la cible du défilement sont décalées
     * d'exactement la hauteur retirée : à l'écran, rien ne bouge.
     */
    private fun trimTextAlreadyScrolledPast() {
        val layout = textView.layout ?: return
        if (layout.lineCount <= visibleLines * BUFFERED_SCREENS) return

        var lastHiddenLine = -1
        for (line in 0 until layout.lineCount - 1) {
            if (layout.getLineBottom(line) > scrollView.scrollY) break
            lastHiddenLine = line
        }
        if (lastHiddenLine < 0) return

        val removedPx = layout.getLineBottom(lastHiddenLine)
        val cutAt = layout.getLineStart(lastHiddenLine + 1)
        // La coupe doit rester dans le texte acquis : le segment en cours est
        // encore réécrit à chaque bloc de son, on n'y touche pas.
        if (cutAt <= 0 || cutAt > committed.length) return

        committed = committed.substring(cutAt)
        textView.text = styledText()
        scrollAnimator.shiftBy(-removedPx)
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

        /**
         * Combien de hauteurs de zone on garde en mémoire avant de jeter le
         * texte déjà lu. Assez pour que le tampon ne se réduise pas à chaque
         * ligne, assez peu pour qu'il reste borné.
         */
        private const val BUFFERED_SCREENS = 6

        /** Intervalle de nouvelle vérification quand l'effacement attend la fin du défilement. */
        private const val RECHECK_DELAY_MS = 500L

        /**
         * Le repère inséré dans le fil de la parole. Volontairement sans
         * espace à l'intérieur : le texte ne peut donc pas être coupé en son
         * milieu par un retour à la ligne, ce qui laisserait un fragment sans
         * style à l'écran et déjouerait la purge du tampon.
         */
        private const val SILENCE_MARKER = "<silence>"

        /**
         * Deux secondes : une respiration entre deux phrases n'est pas un
         * silence, une pause qu'on remarquerait en écoutant, si. Six secondes :
         * là, l'échange s'est arrêté, la reprise repart à la ligne.
         */
        private const val SHORT_SILENCE_MS = 2_000L
        private const val LONG_SILENCE_MS = 6_000L

        /** Assez petit pour ne pas se confondre avec un mot dit, assez grand pour rester lisible de loin. */
        private const val SILENCE_TEXT_SCALE = 0.55f

        /** Bornes de sécurité : une zone très plate ou très haute ne doit produire ni texte illisible ni texte absurde. */
        private const val MIN_TEXT_SIZE_PX = 18f
        private const val MAX_TEXT_SIZE_PX = 220f
    }
}
