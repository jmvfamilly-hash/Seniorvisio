package com.seniorvisio.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.HomeZone
import com.seniorvisio.core.ScreenTheme
import com.seniorvisio.core.TimeContext
import com.seniorvisio.core.TranscriptionSource
import com.seniorvisio.core.UsageStats
import com.seniorvisio.core.WeatherClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.File
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
     * Vrai quand une photo de la galerie occupe la dalle.
     *
     * ═══ ELLE NE PREND LA PLACE DE RIEN ═══
     *
     * La photo est dessinée DERRIÈRE la pile, en plein cadre (voir
     * hotePhotos dans activity_main.xml). Les trois zones restent donc
     * exactement où elles sont, et l'écran ne se réorganise pas : c'est la
     * règle de cet écran — rien ne bouge jamais, Jean retrouve chaque chose au
     * même endroit.
     *
     * Ce drapeau ne sert plus qu'à savoir s'il y a une photo à rendre et si la
     * transcription de la pièce doit se taire, plus à recomposer la pile.
     */
    private var modePhoto = false

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
    /**
     * L'hôte de la visionneuse, en plein cadre sous la pile (voir
     * activity_main.xml et VisionneusePhotos).
     *
     * NULLABLE, et pas par précaution : l'écran d'appel construit ce même
     * contrôleur sur une autre mise en page, où cette vue n'existe pas — il
     * pose sa propre visionneuse par-dessus la vidéo du proche. Un
     * findViewById non nul y aurait fait tomber l'écran d'appel entier.
     */
    private val hotePhotos: ComposeView? = root.findViewById(R.id.hotePhotos)

    /**
     * Ce que la visionneuse a sous les yeux. Des ÉTATS Compose et non des
     * champs ordinaires : c'est leur modification qui déclenche le
     * redessin, sans qu'on ait à reconstruire le contenu de l'hôte à chaque
     * changement de photo.
     */
    private val photosAffichées = mutableStateOf<List<File>>(emptyList())
    private val rangAffiché = mutableStateOf(0)

    /**
     * Prévenu quand JEAN change de photo au doigt.
     *
     * Posé par l'écran hôte, qui seul tient le rang de référence. Vide par
     * défaut plutôt que nul : un glissement avant branchement ne doit rien
     * casser, il n'a simplement personne à prévenir.
     */
    private var surRangChoisi: (Int) -> Unit = {}
    private val boutonSommeil: Button = root.findViewById(R.id.boutonSommeil)

    private val textMomentIcon: TextView = root.findViewById(R.id.textMomentIcon)
    private val textMomentLabel: TextView = root.findViewById(R.id.textMomentLabel)
    private val textMomentWeatherSeparator: TextView = root.findViewById(R.id.textMomentWeatherSeparator)
    private val textWeatherIcon: TextView = root.findViewById(R.id.textWeatherIcon)
    private val textWeatherLabel: TextView = root.findViewById(R.id.textWeatherLabel)
    private val textConsigneSousTitres: TextView = root.findViewById(R.id.textConsigneSousTitres)
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
     * La galerie, sur toute la dalle.
     *
     * ═══ DERRIÈRE LES ZONES, ET NON DEDANS ═══
     *
     * Elle est posée sur la vue de fond, DERRIÈRE la pile des trois zones, et
     * non dans la pile : des photos rangées dans une zone n'auraient occupé
     * qu'un tiers de la dalle, quand ce qu'on veut montrer à Jean est la
     * photo, pas son timbre-poste.
     *
     * ═══ DES FICHIERS, ET PLUS UN BITMAP ═══
     *
     * Cette méthode recevait une image déjà décodée, et cette classe gérait sa
     * mémoire à la main — recyclage, ordre de pose, comptage. Tout cela est
     * parti avec la visionneuse : c'est Coil qui décode, Telephoto qui ne lit
     * que les tuiles visibles, et personne ici qui n'a plus de pixels à
     * reprendre.
     *
     * Elle reçoit la LISTE ENTIÈRE et non la photo courante, parce que c'est
     * le pager qui fait glisser d'une image à l'autre : lui donner les photos
     * une par une, ce serait lui retirer ce pour quoi on l'a pris.
     *
     * Une liste vide retire la galerie — c'est ce que fait [masquerPhotos].
     */
    fun afficherPhotos(fichiers: List<File>, rang: Int) {
        modePhoto = fichiers.isNotEmpty()
        photosAffichées.value = fichiers
        rangAffiché.value = rang.coerceAtLeast(0)
        hotePhotos?.visibility = if (modePhoto) View.VISIBLE else View.GONE
    }

    /**
     * Retire la galerie et rend la dalle au fond uni.
     *
     * La pile n'est pas touchée : elle n'a jamais bougé. La date, la météo et
     * les deux zones de texte étaient devant les photos pendant tout ce temps.
     */
    fun masquerPhotos() {
        if (!modePhoto) return
        afficherPhotos(emptyList(), 0)
    }

    /**
     * Branche ce qui doit savoir que Jean a fait glisser l'image.
     *
     * Le rang de référence vit dans l'écran hôte, pas ici : cette classe
     * dessine, elle ne décide pas de ce qui s'affiche. Sans ce retour, un
     * glissement serait effacé au prochain changement de créneau, qui
     * reposerait le rang que l'ordonnanceur croit courant.
     */
    fun brancherRangPhoto(surRang: (Int) -> Unit) {
        surRangChoisi = surRang
    }

    /**
     * Le bouton de sommeil, à droite du bandeau de la date.
     *
     * Branché par l'écran hôte : c'est lui qui possède la fenêtre dont il faut
     * retirer le maintien allumé (voir MiseEnVeille).
     */
    fun brancherSommeil(surSommeil: () -> Unit) {
        boutonSommeil.setOnClickListener {
            UsageStats.noteGeste(UsageStats.GESTE_SOMMEIL)
            surSommeil()
        }
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
     * qu'on voit. Une bande posée sous zoneInfo.bottom perdrait donc un tiers
     * d'écran pour rien, et une bande posée sous le texte, si on l'avait
     * devinée à la main, se serait décalée à la première retouche.
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
        // ═══ POSÉ UNE SEULE FOIS, ET C'EST LE POINT ═══
        //
        // setContent décrit ce qu'il faut afficher EN FONCTION d'états, il ne
        // pose pas une image. Le rappeler à chaque changement de photo
        // reconstruirait l'arbre entier — donc le pager, donc son état de
        // défilement — et le glissement repartirait de zéro à chaque créneau.
        //
        // Ce sont les deux états lus ici qui déclenchent le redessin quand
        // afficherPhotos les modifie.
        hotePhotos?.setContent {
            val fichiers by photosAffichées
            val rang by rangAffiché
            VisionneusePhotos(
                photos = fichiers,
                rang = rang,
                // Passe par le champ et non directement par surRangChoisi :
                // le branchement peut arriver après la pose du contenu, et
                // capturer la valeur d'alors figerait un rappel vide.
                surRang = { surRangChoisi(it) },
            )
        }
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
        val views = mapOf(HomeZone.INFO to zoneInfo, HomeZone.ROOM to zoneRoom, HomeZone.CALL to zoneCall)
        val ordered = adminConfig.zoneOrder.mapNotNull { views[it] }
            .takeIf { it.size == views.size } ?: return
        val déjàEnPlace = zoneStack.childCount == ordered.size &&
            ordered.withIndex().all { (index, view) -> zoneStack.getChildAt(index) === view }
        if (déjàEnPlace) return
        zoneStack.removeAllViews()
        ordered.forEach { zoneStack.addView(it) }
    }

    /**
     * Le fond de la bande d'information.
     *
     * ═══ IL RESTE, MÊME DEVANT UNE PHOTO ═══
     *
     * Cet aplat existe pour que la date reste lisible quand elle se retrouve
     * posée sur la vidéo d'un proche, dont les couleurs sont quelconques. Une
     * photo de famille pose exactement le même problème — c'est même le cas le
     * plus fréquent maintenant que la galerie occupe la dalle en permanence.
     *
     * Il a été effacé un temps en mode photo, pour ne pas masquer le haut de
     * l'image. Mais la consigne est que la date et la météo soient au premier
     * plan hors appel : un repère illisible sur un ciel clair n'est pas un
     * repère. L'aplat de la palette est déjà semi-transparent (voir
     * ScreenTheme), la photo se voit au travers.
     */
    private fun applyPalette(palette: ScreenTheme.Palette) {
        val rayon = ZONE_CORNER_RADIUS_DP * context.resources.displayMetrics.density
        zoneInfo.background = GradientDrawable().apply {
            cornerRadius = rayon
            setColor(palette.zoneBackground)
        }
        listOf(textMomentLabel, textWeatherLabel, textClockDate).forEach {
            it.setTextColor(palette.primaryText)
        }
        textMomentWeatherSeparator.setTextColor(palette.secondaryText)
        textConsigneSousTitres.setTextColor(palette.secondaryText)
        // La zone d'information reçoit le même fond que les deux autres, alors
        // qu'elle n'a pas de texte à faire ressortir en temps normal : pendant
        // un appel, elle se retrouve posée sur la vidéo du proche, dont les
        // couleurs sont quelconques. Sans fond, sa date devenait illisible dès
        // que la scène filmée était claire.
        textMomentIcon.setTextColor(palette.primaryText)
        textWeatherIcon.setTextColor(palette.primaryText)
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
         * Exposé et non privé : les blocs de l'écran d'appel doivent avoir
         * EXACTEMENT le même fond, et recopier « 16f » là-bas rouvrirait la
         * divergence qu'on vient de fermer — deux valeurs qui s'écartent le
         * jour où l'une des deux est retouchée, sans que rien ne le signale.
         */
        const val ZONE_CORNER_RADIUS_DP = 16f

        /** Même durée de fondu que les zones de texte, pour que tout l'écran respire au même rythme. */
        private const val FADE_MS = 400L

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    }
}
