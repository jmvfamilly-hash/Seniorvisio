package com.seniorvisio.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
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
    root: View,
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
    fun submitTranscription(source: TranscriptionSource, text: String, isFinal: Boolean) {
        zoneFor(source).submit(text, isFinal)
    }

    /** Vide les deux zones de texte immédiatement (fin d'appel, sortie d'écran). */
    fun clearTranscriptions() {
        roomZone.clear()
        callZone.clear()
    }

    /** Réglages d'affichage communs aux deux zones — elles obéissent aux mêmes règles. */
    fun setVisibleLines(lines: Int) {
        roomZone.setVisibleLines(lines)
        callZone.setVisibleLines(lines)
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
     */
    fun setBackground(background: Background) {
        if (currentBackground == background) return
        currentBackground = background
        val visible = background == Background.SOLID
        zoneInfo.animate().cancel()
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

    init {
        applyZoneOrder()
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
        if (ordered.size != views.size) return
        val alreadyInOrder = ordered.withIndex().all { (index, view) -> zoneStack.getChildAt(index) === view }
        if (alreadyInOrder) return
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
        private const val ZONE_CORNER_RADIUS_DP = 16f

        /** Même durée de fondu que les zones de texte, pour que tout l'écran respire au même rythme. */
        private const val FADE_MS = 400L

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    }
}
