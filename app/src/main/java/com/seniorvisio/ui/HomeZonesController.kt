package com.seniorvisio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.HomeZone
import com.seniorvisio.core.ScreenTheme
import com.seniorvisio.core.TimeContext
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.WeatherClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * La couche d'affichage de l'écran de Jean : trois zones empilées (voir
 * view_home_zones.xml), chacune occupant un tiers de la hauteur, qui réagissent
 * à leur contexte sans rien savoir de ce qui le produit.
 *
 * Elle ignore délibérément s'il y a un appel en cours, qui a décroché, ou qui a
 * appuyé sur quoi. Elle ne connaît que deux choses :
 *
 *  - **d'où vient un texte** — sa source (voir TranscriptionSource) suffit à
 *    déterminer sa zone, via l'unique table de correspondance [zoneFor] ;
 *  - **ce qu'il y a derrière** — un fond uni, une vidéo ou un diaporama (voir
 *    [setBackground]), ce qui décide si le repère date/météo a sa place.
 *
 * Le même objet sert à l'écran d'accueil et à l'écran d'appel, qui n'en sont
 * pas deux mais un seul du point de vue de Jean : les zones ne bougent pas
 * quand un appel arrive, seul le fond change. Factoriser ici évite que les deux
 * écrans ne divergent — c'est exactement ce qui était arrivé au repère
 * temporel, dupliqué dans les deux et déjà légèrement différent de l'un à
 * l'autre.
 *
 * Les deux zones de texte partagent le même code d'affichage (voir
 * RollingCaptionZone) et les mêmes réglages : même défilement, même
 * effacement, même nombre de lignes. Rien ne les distingue que leur source.
 */
class HomeZonesController(
    private val root: View,
    /**
     * Appelé à chaque changement de palette, pour que l'écran hôte repeigne
     * ce qu'il possède en propre (son fond hors appel, ses boutons) — les
     * zones, elles, sont déjà traitées ici.
     */
    private val onPalette: (ScreenTheme.Palette) -> Unit,
) {

    /**
     * Contenu de la zone 1 à un instant donné, tel qu'affiché — publié vers le
     * PWA pour qu'il montre au proche la même chose que Jean plutôt que de le
     * recalculer de son côté, ce qui divergerait immanquablement (météo de la
     * ville du proche, fuseau horaire différent, formats de date...).
     */
    data class InfoSnapshot(
        val moment: String,
        val weather: String?,
        val date: String,
    )

    /** Prévenu à chaque rafraîchissement de la zone 1 (voir updateInfoZone). */
    var onInfoChanged: ((InfoSnapshot) -> Unit)? = null

    /** Ce qui occupe le fond de l'écran derrière les zones (voir setBackground). */
    enum class Background { SOLID, VIDEO, SLIDESHOW }

    private var currentBackground = Background.SOLID

    /**
     * Vrai quand un titre d'actualité occupe les deux zones du bas.
     *
     * Ce n'est pas un affichage de plus posé par-dessus : l'actualité REMPLACE
     * les deux zones de texte dans la pile. L'écran de Jean n'a alors que deux
     * surfaces — le bandeau de la date, et le titre — ce qui est exactement la
     * règle de cet écran : jamais plus de choses à regarder qu'il n'en faut.
     */
    private var modeActualite = false

    /** Ordre courant des zones, sous la forme attendue par le PWA ("INFO,ROOM,CALL"). */
    fun zoneOrderNames(): String = adminConfig.zoneOrder.joinToString(",") { it.name }

    private val context: Context = root.context
    private val adminConfig = AdminConfig(context)
    private val weatherClient = WeatherClient(context)
    private val clockHandler = Handler(Looper.getMainLooper())

    private val zoneStack: LinearLayout = root.findViewById(R.id.zoneStack)
    private val zoneInfo: View = root.findViewById(R.id.zoneInfo)
    private val zoneRoom: View = root.findViewById(R.id.zoneRoom)
    private val zoneCall: View = root.findViewById(R.id.zoneCall)
    private val zoneActualite: View = root.findViewById(R.id.zoneActualite)
    private val imageActualite: ImageView = root.findViewById(R.id.imageActualiteAccueil)
    // La colonne qui porte la photo ET le crédit du photographe. C'est elle
    // qu'on montre ou qu'on cache : masquer la seule image laisserait la ligne
    // de crédit flotter sous une photo absente.
    private val colonneImageActualite: View = root.findViewById(R.id.colonneImageAccueil)
    private val creditActualite: TextView = root.findViewById(R.id.creditActualiteAccueil)

    /**
     * La vignette actuellement posée sur la vue, pour pouvoir la reprendre
     * quand la suivante arrive. Retenue ici et nulle part ailleurs : c'est
     * cette classe qui sait ce qui est affiché, donc elle seule sait ce qui ne
     * l'est plus.
     */
    private var vignetteActuelle: Bitmap? = null
    private val texteActualite: TextView = root.findViewById(R.id.texteActualiteAccueil)
    private val boutonActualitePrecedente: Button = root.findViewById(R.id.boutonActualitePrecedente)
    private val boutonActualiteSuivante: Button = root.findViewById(R.id.boutonActualiteSuivante)
    private val origineActualite: TextView = root.findViewById(R.id.origineActualiteAccueil)
    private val boutonSommeil: Button = root.findViewById(R.id.boutonSommeil)

    private val textMomentIcon: TextView = root.findViewById(R.id.textMomentIcon)
    private val textMomentLabel: TextView = root.findViewById(R.id.textMomentLabel)
    private val textMomentWeatherSeparator: TextView = root.findViewById(R.id.textMomentWeatherSeparator)
    private val textWeatherIcon: TextView = root.findViewById(R.id.textWeatherIcon)
    private val textWeatherLabel: TextView = root.findViewById(R.id.textWeatherLabel)
    private val textClockDate: TextView = root.findViewById(R.id.textClockDate)

    private val roomZone = RollingCaptionZone(
        container = zoneRoom,
        scrollView = root.findViewById<ScrollView>(R.id.roomCaptionScroll),
        textView = root.findViewById<TextView>(R.id.textRoomCaption),
    )

    private val callZone = RollingCaptionZone(
        container = zoneCall,
        scrollView = root.findViewById<ScrollView>(R.id.callCaptionScroll),
        textView = root.findViewById<TextView>(R.id.textCallCaption),
    )

    /**
     * La seule table de correspondance entre une source de son et l'endroit où
     * son texte s'affiche. Aucun appelant n'a à connaître les zones : il dit
     * d'où vient le son, la couche d'affichage s'occupe du reste.
     */
    private fun zoneFor(source: TranscriptionSource) = when (source) {
        TranscriptionSource.ROOM -> roomZone
        TranscriptionSource.CALL -> callZone
    }

    /** Nouveau texte transcrit : il va dans la zone que sa source désigne. */
    /**
     * `fromJean` n'a de sens que pour la pièce : dans un appel, la source est
     * la voix du proche à l'autre bout, jamais celle de Jean.
     */
    fun submitTranscription(
        source: TranscriptionSource,
        text: String,
        isFinal: Boolean,
        fromJean: Boolean = false,
    ) {
        zoneFor(source).submit(text, isFinal, fromJean)
    }

    /**
     * Montre un titre d'actualité à la place des deux zones de texte.
     *
     * La vignette n'apparaît QUE si l'article en fournit une : réserver sa
     * place quand elle manque donnerait un titre serré à droite d'un vide
     * inexpliqué, et ce flux-ci n'illustre pas tous ses articles.
     *
     * Les zones de texte sont vidées au passage. Sans ça, une phrase de la
     * pièce restée en mémoire réapparaîtrait telle quelle à la fin de
     * l'actualité, des heures après avoir été prononcée.
     */
    fun afficherActualite(
        texte: String,
        vignette: Bitmap?,
        origine: String? = null,
        crédit: String? = null,
    ) {
        texteActualite.text = texte
        // Masquée quand le fil ne se nomme pas — le cas de beaucoup de flux.
        // Une ligne vide sous le titre prendrait de la hauteur sur une zone qui
        // en manque déjà, et le titre descendrait d'autant vers son plancher de
        // taille.
        if (origine.isNullOrBlank()) {
            origineActualite.visibility = View.GONE
        } else {
            origineActualite.text = origine
            origineActualite.visibility = View.VISIBLE
        }
        // ═══ L'ANCIENNE VIGNETTE EST RENDUE, ET C'EST LE CŒUR DU CORRECTIF ═══
        //
        // Depuis Android 8 les pixels d'un bitmap vivent dans le tas NATIF,
        // mais le ramasse-miettes se déclenche sur la pression du tas JAVA.
        // Celui-ci est resté entre 9 et 20 mégaoctets sur 192 pendant que le
        // natif montait à 883 : jamais assez plein pour qu'une collecte parte.
        // Les vignettes mortes n'étaient donc JAMAIS reprises, et le système
        // annonçait sans arrêt qu'il allait tuer des services.
        //
        // recycle() rend les octets tout de suite, sans attendre une collecte
        // qui n'arrive pas.
        //
        // L'ORDRE COMPTE, ET LA GARDE AUSSI. Recycler un bitmap encore posé
        // sur une vue fait planter le dessin à la frame suivante. On pose donc
        // le nouveau D'ABORD, puis on reprend l'ancien — et jamais s'il s'agit
        // de la même instance, ce qui arriverait si le même titre était
        // réaffiché.
        val ancienne = vignetteActuelle
        if (vignette != null) {
            imageActualite.setImageBitmap(vignette)
            colonneImageActualite.visibility = View.VISIBLE
        } else {
            imageActualite.setImageDrawable(null)
            colonneImageActualite.visibility = View.GONE
        }
        vignetteActuelle = vignette
        if (ancienne != null && ancienne !== vignette && !ancienne.isRecycled) {
            ancienne.recycle()
        }
        // Sous la photo, et seulement s'il y a une photo : un crédit de
        // photographe sans photographie ne se rapporte à rien.
        if (crédit.isNullOrBlank() || vignette == null) {
            creditActualite.visibility = View.GONE
        } else {
            creditActualite.text = crédit
            creditActualite.visibility = View.VISIBLE
        }
        if (!modeActualite) {
            modeActualite = true
            roomZone.clear()
            callZone.clear()
            applyZoneOrder()
        }
    }

    /** Rend la place aux deux zones de texte. */
    fun masquerActualite() {
        if (!modeActualite) return
        modeActualite = false
        imageActualite.setImageDrawable(null)
        // Quitter le fil d'information rend aussi la dernière vignette : sans
        // cela, huit mégaoctets restaient retenus tant que l'écran vivait.
        vignetteActuelle?.takeIf { !it.isRecycled }?.recycle()
        vignetteActuelle = null
        applyZoneOrder()
    }

    val actualiteAffichee: Boolean get() = modeActualite

    /**
     * Branche la navigation de Jean sur le fil d'information.
     *
     * Les rappels sont fournis par l'écran d'accueil, qui seul sait où en est
     * la liste : ce contrôleur dessine, il ne décide pas de ce qui s'affiche.
     *
     * [surSwipe] reçoit vrai pour « suivant », faux pour « précédent ».
     */
    /**
     * Le bouton de sommeil, à droite du bandeau de la date.
     *
     * Branché par l'écran hôte : c'est lui qui possède la fenêtre dont il faut
     * retirer le maintien allumé (voir MiseEnVeille).
     */
    fun brancherSommeil(surSommeil: () -> Unit) {
        boutonSommeil.setOnClickListener { surSommeil() }
    }

    fun brancherNavigationActualite(
        surPrécédent: () -> Unit,
        surSuivant: () -> Unit,
        surSwipe: (Boolean) -> Unit,
    ) {
        boutonActualitePrecedente.setOnClickListener { surPrécédent() }
        boutonActualiteSuivante.setOnClickListener { surSuivant() }
        GlissementHorizontal.brancher(zoneActualite, surSwipe)
    }

    /**
     * Grise le bouton qui ne mène nulle part.
     *
     * Grisé et non masqué : un bouton qui disparaît déplace celui d'à côté, et
     * la cible que Jean visait n'est plus là où il l'a vue. Sur une main qui
     * tremble, un déplacement de dernière seconde est pire qu'un bouton inerte.
     */
    fun majNavigationActualite(rang: Int, total: Int) {
        boutonActualitePrecedente.isEnabled = rang > 0
        boutonActualiteSuivante.isEnabled = rang < total - 1
        boutonActualitePrecedente.alpha = if (rang > 0) 1f else 0.4f
        boutonActualiteSuivante.alpha = if (rang < total - 1) 1f else 0.4f
    }

    /** Vide les deux zones de texte immédiatement (fin d'appel, sortie d'écran). */
    fun clearTranscriptions() {
        roomZone.clear()
        callZone.clear()
    }

    /** Réglages d'affichage communs aux deux zones — elles obéissent aux mêmes règles. */
    /**
     * Géométrie des zones de texte telle qu'elle est réellement rendue, pour
     * que le PWA puisse en reproduire les coupures de ligne (voir
     * RollingCaptionZone.charsPerLine). Les deux zones partagent les mêmes
     * réglages : mesurer l'une suffit.
     */
    fun captionCharsPerLine(): Int = roomZone.charsPerLine()

    fun captionLines(): Int = roomZone.visibleLines()

    fun setVisibleLines(lines: Int) {
        roomZone.setVisibleLines(lines)
        callZone.setVisibleLines(lines)
    }

    /**
     * Les DEUX zones, et non la seule zone d'appel.
     *
     * L'administrateur a demandé ce réglage « au moins pour les appels à
     * distance ». Le poser sur une seule des deux romprait l'invariant de cet
     * écran — les deux zones obéissent aux mêmes règles, rien ne les distingue
     * que leur source — et donnerait deux pavés de texte d'aspect différent
     * l'un au-dessus de l'autre, ce que personne n'a demandé.
     */
    fun setCaptionLineSpacing(multiplier: Float) {
        roomZone.setLineSpacingMultiplier(multiplier)
        callZone.setLineSpacingMultiplier(multiplier)
    }

    fun setScrollSpeedDpPerSec(dpPerSec: Float) {
        roomZone.setScrollSpeedDpPerSec(dpPerSec)
        callZone.setScrollSpeedDpPerSec(dpPerSec)
    }

    fun setClearDelaySeconds(seconds: Int) {
        roomZone.setClearDelaySeconds(seconds)
        callZone.setClearDelaySeconds(seconds)
    }

    /** Ce que Jean a réellement sous les yeux dans la zone de cette source, null si elle est vide. */
    fun displayedText(source: TranscriptionSource): String? = zoneFor(source).displayedText()

    /**
     * Vrai tant qu'au moins une zone a du texte à l'écran. L'écran hôte s'en
     * sert pour ne pas laisser la tablette s'endormir au milieu d'une phrase
     * (voir MainActivity) : la veille reprend ses droits une fois que tout a
     * été affiché, pas quand le bruit s'arrête.
     */
    fun hasTextOnScreen(): Boolean = roomZone.hasText() || callZone.hasText()

    /** Le plus grand retard de lecture des deux zones — une seule a une source à la fois. */
    fun pendingSeconds(): Float = maxOf(roomZone.pendingSeconds(), callZone.pendingSeconds())

    /**
     * Ce qu'il y a derrière les zones. La zone d'information s'efface en fondu
     * dès qu'une image occupe le fond : la date et la météo sont un repère
     * pour un écran au repos, pas quelque chose qui doive rester posé sur le
     * visage du proche ou sur une photo de famille. Sa place reste réservée
     * (invisible, pas retirée) pour que les deux zones de texte ne bougent
     * pas d'un pixel au passage.
     *
     * @param animate à passer à false quand l'état est fixé avant même que
     *   l'écran soit visible (voir IncomingCallActivity, qui masque le
     *   bandeau dès la sonnerie) : un fondu partirait alors d'un bandeau
     *   affiché une demi-seconde, ce que personne n'a demandé à voir.
     */
    fun setBackground(background: Background, animate: Boolean = true) {
        if (currentBackground == background) return
        currentBackground = background
        val visible = background == Background.SOLID
        zoneInfo.animate().cancel()
        if (!animate) {
            zoneInfo.alpha = if (visible) 1f else 0f
            zoneInfo.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            return
        }
        if (visible) zoneInfo.visibility = View.VISIBLE
        zoneInfo.animate().alpha(if (visible) 1f else 0f).setDuration(FADE_MS)
            .withEndAction { if (!visible) zoneInfo.visibility = View.INVISIBLE }
            .start()
    }

    private val themeMonitor = ScreenTheme.Monitor(context) { palette -> applyPalette(palette) }

    /**
     * Recalé sur le quart d'heure suivant à chaque tour, pas chaque minute :
     * le moment de la journée ne change que quelques fois par jour, inutile
     * de réveiller le processeur plus souvent sur une tablette allumée en
     * permanence. La palette est réévaluée au passage, l'heure étant l'un de
     * ses deux critères (voir ScreenTheme).
     */
    private val clockTicker = object : Runnable {
        override fun run() {
            updateInfoZone()
            themeMonitor.refresh()
            val quarterHourMs = 15 * 60_000L
            clockHandler.postDelayed(this, quarterHourMs - (System.currentTimeMillis() % quarterHourMs))
        }
    }

    /**
     * Prévenu chaque fois qu'une zone de texte apparaît ou disparaît.
     *
     * Seul l'écran d'appel s'en sert : la vidéo du proche n'occupe que la
     * bande laissée libre au-dessus du texte, et reprend toute la hauteur
     * quand plus rien n'est affiché (voir IncomingCallActivity). L'écran
     * d'accueil ne s'y abonne pas — là, rien ne doit jamais bouger.
     */
    var onTextZonesChanged: (() -> Unit)? = null

    /**
     * Ordonnée du haut de la première zone de TEXTE actuellement affichée, en
     * pixels dans le repère de l'écran — ou null si aucune ne l'est.
     *
     * La zone d'information ne compte pas : pendant un appel elle est déjà
     * effacée (voir setBackground), et la vidéo peut occuper sa place.
     *
     * L'ordre des zones étant réglable (voir applyZoneOrder), on ne peut pas
     * se contenter de supposer que le texte est en bas : on parcourt la pile
     * telle qu'elle est réellement empilée à cet instant.
     */
    /**
     * Le bas de ce qui est ÉCRIT dans la bande d'information, en coordonnées
     * de la racine.
     *
     * ═══ POURQUOI PAS SIMPLEMENT zoneInfo.bottom ═══
     *
     * Parce que la boîte et son contenu ne coïncident pas. Pendant un appel,
     * zoneInfo pèse un tiers de la hauteur de l'écran alors qu'elle n'y écrit
     * que deux lignes et un bouton : son bas se trouve très en dessous de ce
     * qu'on voit. Une bande d'actualité posée sous zoneInfo.bottom perdrait
     * donc un tiers d'écran pour rien, et une bande posée sous le texte, si on
     * l'avait devinée à la main, se serait décalée à la première retouche.
     *
     * Le repère demandé est « sous la date » sur l'accueil et « sous le bouton
     * de sommeil » pendant un appel. Ce sont les deux mêmes vues, et c'est la
     * PLUS BASSE des deux qui convient dans les deux cas : prendre le maximum
     * répond aux deux formulations sans avoir à savoir laquelle s'applique.
     *
     * offsetDescendantRectToMyCoords plutôt qu'une somme de .top : la bande
     * d'information est imbriquée différemment selon l'écran, et un calcul qui
     * suppose la profondeur casse le jour où quelqu'un ajoute un conteneur.
     */
    fun basDuContenuInfo(): Int {
        val racine = root as? ViewGroup ?: return 0
        fun basDe(vue: View): Int {
            if (vue.visibility != View.VISIBLE || vue.height == 0) return 0
            val r = Rect(0, 0, vue.width, vue.height)
            racine.offsetDescendantRectToMyCoords(vue, r)
            return r.bottom
        }
        return maxOf(basDe(textClockDate), basDe(boutonSommeil))
    }

    fun topOfVisibleTextZones(): Int? {
        for (index in 0 until zoneStack.childCount) {
            val child = zoneStack.getChildAt(index)
            val displayed = when (child) {
                zoneRoom -> roomZone.isDisplayed
                zoneCall -> callZone.isDisplayed
                else -> false
            }
            if (displayed) return zoneStack.top + child.top
        }
        return null
    }

    init {
        applyZoneOrder()
        roomZone.onDisplayChanged = { onTextZonesChanged?.invoke() }
        callZone.onDisplayChanged = { onTextZonesChanged?.invoke() }
    }

    /**
     * À appeler depuis onResume de l'écran hôte : la tablette peut rester des
     * heures écran éteint, le repère temporel et la palette doivent être
     * justes dès qu'elle se rallume, pas au prochain quart d'heure.
     */
    fun onResume() {
        // L'ordre a pu changer pendant que l'admin était dans ses réglages.
        applyZoneOrder()
        themeMonitor.start()
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.post(clockTicker)
    }

    fun onPause() {
        clockHandler.removeCallbacks(clockTicker)
        themeMonitor.stop()
    }

    fun release() {
        onPause()
        roomZone.release()
        callZone.release()
    }

    /**
     * Réordonne les zones selon le réglage admin. Les vues sont retirées puis
     * remises dans le nouvel ordre plutôt que recréées : leur contenu, leur
     * état d'affichage et l'animation en cours survivent au changement.
     */
    private fun applyZoneOrder() {
        // En mode actualité, la pile ne contient plus que deux surfaces. Le
        // choix se fait ICI et nulle part ailleurs : c'est déjà la seule
        // fonction qui décide de la composition de la pile, et un second
        // endroit qui y toucherait finirait par la contredire.
        val ordered = if (modeActualite) {
            listOf(zoneInfo, zoneActualite)
        } else {
            val views = mapOf(HomeZone.INFO to zoneInfo, HomeZone.ROOM to zoneRoom, HomeZone.CALL to zoneCall)
            adminConfig.zoneOrder.mapNotNull { views[it] }
                .takeIf { it.size == views.size } ?: return
        }
        zoneActualite.visibility = if (modeActualite) View.VISIBLE else View.GONE
        // ═══ LA BANDE D'INFORMATION REND SON TIERS AUX TITRES ═══
        //
        // En mode actualité la pile ne compte que deux surfaces, de poids 1 et
        // 2 : un tiers de l'écran revenait donc à zoneInfo, pour deux lignes de
        // texte et un bouton. Les titres n'occupaient que les deux tiers
        // restants, et le vide au-dessus se voyait.
        //
        // Elle passe à sa hauteur utile, et zoneActualite — seule vue pondérée
        // qui reste — prend tout le reste. Les titres commencent donc juste
        // sous la date, et s'arrêtent au-dessus des boutons de navigation, qui
        // sont déjà dans zoneActualite et hors de la rangée pondérée.
        //
        // Hors mode actualité, rien ne change : les trois zones se partagent la
        // hauteur comme avant, et l'ordre reste celui de l'administrateur.
        (zoneInfo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val hauteurVoulue = if (modeActualite) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                0
            }
            val poidsVoulu = if (modeActualite) 0f else 1f
            if (lp.height != hauteurVoulue || lp.weight != poidsVoulu) {
                lp.height = hauteurVoulue
                lp.weight = poidsVoulu
                zoneInfo.layoutParams = lp
            }
        }
        val déjàEnPlace = zoneStack.childCount == ordered.size &&
            ordered.withIndex().all { (index, view) -> zoneStack.getChildAt(index) === view }
        if (déjàEnPlace) return
        zoneStack.removeAllViews()
        ordered.forEach { zoneStack.addView(it) }
    }

    private fun applyPalette(palette: ScreenTheme.Palette) {
        listOf(textMomentLabel, textWeatherLabel, textClockDate).forEach {
            it.setTextColor(palette.primaryText)
        }
        textMomentWeatherSeparator.setTextColor(palette.secondaryText)
        // La zone d'information reçoit le même fond que les deux autres, alors
        // qu'elle n'a pas de texte à faire ressortir en temps normal : pendant
        // un appel, elle se retrouve posée sur la vidéo du proche, dont les
        // couleurs sont quelconques. Sans fond, sa date devenait illisible dès
        // que la scène filmée était claire.
        zoneInfo.background = GradientDrawable().apply {
            cornerRadius = ZONE_CORNER_RADIUS_DP * context.resources.displayMetrics.density
            setColor(palette.zoneBackground)
        }
        textMomentIcon.setTextColor(palette.primaryText)
        textWeatherIcon.setTextColor(palette.primaryText)
        texteActualite.setTextColor(palette.primaryText)
        // L'origine et le crédit aussi, alors qu'ils héritaient jusqu'ici du
        // défaut du thème. Ce défaut n'était identique à la palette par aucune
        // règle : il se trouvait simplement lui ressembler. L'écran d'appel
        // venant d'être aligné sur cette palette, les laisser hériter aurait
        // fait un TROISIÈME comportement — celui qui se remarque le jour où le
        // thème change et où deux lignes sur trois suivent.
        //
        // Leur mise en retrait est portée par la transparence posée dans la
        // mise en page, pas par une teinte à part.
        origineActualite.setTextColor(palette.primaryText)
        creditActualite.setTextColor(palette.primaryText)
        zoneActualite.background = GradientDrawable().apply {
            cornerRadius = ZONE_CORNER_RADIUS_DP * context.resources.displayMetrics.density
            setColor(palette.zoneBackground)
        }
        roomZone.applyColors(palette.primaryText, palette.zoneBackground)
        callZone.applyColors(palette.primaryText, palette.zoneBackground)
        onPalette(palette)
    }

    /**
     * Pictogramme + mot ("🌤️ Après-midi") à la place d'une heure exacte : se
     * reconnaît d'un coup d'œil, là où "14:35" demande de déchiffrer deux
     * nombres. Ce qui compte au quotidien, c'est de savoir où on en est dans
     * la journée. Date en toutes lettres pour la même raison.
     *
     * La météo (voir WeatherClient) est demandée à chaque tour mais ne fait un
     * vrai appel réseau qu'une fois par heure (cache interne) : rien à gérer
     * de spécial ici, juste rappeler la fonction régulièrement.
     */
    private fun updateInfoZone() {
        val now = LocalDateTime.now()
        val moment = TimeContext.momentOfDay(now.hour)
        val date = now.format(DATE_FORMAT).replaceFirstChar { it.titlecase(Locale.FRENCH) }
        textMomentIcon.text = moment.icon
        textMomentLabel.text = moment.label
        textClockDate.text = date

        weatherClient.fetchWeather { weather ->
            val visibility = if (weather == null) View.GONE else View.VISIBLE
            textMomentWeatherSeparator.visibility = visibility
            textWeatherIcon.visibility = visibility
            textWeatherLabel.visibility = visibility
            if (weather != null) {
                textWeatherIcon.text = weather.icon
                textWeatherLabel.text = weather.label
            }
            onInfoChanged?.invoke(
                InfoSnapshot(
                    moment = "${moment.icon} ${moment.label}",
                    weather = weather?.let { "${it.icon} ${it.label}" },
                    date = date,
                )
            )
        }
    }

    companion object {
        /** Même arrondi que les deux zones de texte (voir RollingCaptionZone). */
        /**
         * Le rayon des coins des zones, partagé avec l'écran d'appel.
         *
         * Exposé et non privé : le bloc d'actualité de l'écran d'appel doit
         * avoir EXACTEMENT le même fond, et recopier « 16f » là-bas rouvrirait
         * la divergence qu'on vient de fermer — deux valeurs qui s'écartent le
         * jour où l'une des deux est retouchée, sans que rien ne le signale.
         */
        const val ZONE_CORNER_RADIUS_DP = 16f

        /** Même durée de fondu que les zones de texte, pour que tout l'écran respire au même rythme. */
        private const val FADE_MS = 400L

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    }
}
