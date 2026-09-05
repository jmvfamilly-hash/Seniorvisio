package com.seniorvisio.core

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Palette de l'écran de Jean, choisie en fonction du moment de la journée et
 * de la luminosité réellement mesurée dans la pièce.
 *
 * Une tablette allumée en permanence dans une chambre pose deux problèmes
 * opposés : en plein jour, un fond sombre renvoie surtout des reflets et le
 * texte devient illisible ; le soir, un fond clair éblouit et empêche de
 * dormir. D'où deux palettes complètes plutôt qu'un simple réglage de
 * luminosité du rétroéclairage, qui ne change pas le contraste réel entre le
 * texte et son fond.
 *
 * L'heure ne suffit pas seule : à 15h volets fermés, la pièce est sombre. Le
 * capteur de lumière ambiante, présent sur pratiquement toutes les tablettes
 * et gratuit à consulter, tranche donc ces cas — sans lui (capteur absent ou
 * pas encore de mesure), on retombe simplement sur l'heure.
 */
object ScreenTheme {

    data class Palette(
        val isDark: Boolean,
        /** Fond de l'écran hors appel (la vidéo le remplace pendant un appel). */
        val background: Int,
        /** Fond des zones posées par-dessus, volontairement translucide pour rester lisible sur la vidéo. */
        val zoneBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
    )

    private val DARK = Palette(
        isDark = true,
        background = Color.parseColor("#101A2E"),
        zoneBackground = Color.parseColor("#CC0B1220"),
        primaryText = Color.parseColor("#FFFFFF"),
        secondaryText = Color.parseColor("#AEBBD0"),
    )

    private val LIGHT = Palette(
        isDark = false,
        background = Color.parseColor("#F2F5FA"),
        // Presque blanc et franchement opaque : posée sur une vidéo d'appel
        // aux couleurs quelconques, une zone claire trop transparente rendait
        // le texte noir illisible dès que la scène filmée était claire.
        zoneBackground = Color.parseColor("#F0FFFFFF"),
        primaryText = Color.parseColor("#101A2E"),
        secondaryText = Color.parseColor("#4A5A75"),
    )

    /**
     * `ambientLux` null = pas de capteur ou pas encore de mesure : l'heure
     * décide seule.
     *
     * Les deux seuils ne sont pas une coquette : un seuil unique faisait
     * basculer la palette d'avant en arrière à chaque passage de nuage ou
     * chaque personne passant devant la tablette. Il faut donc une pièce
     * franchement éclairée pour repasser en clair, et franchement sombre pour
     * repasser en foncé — d'où le paramètre `currentlyDark`, qui rend la
     * décision volontairement collante.
     */
    fun paletteFor(hour: Int, ambientLux: Float?, currentlyDark: Boolean): Palette {
        val moment = TimeContext.momentOfDay(hour)
        val nightHours = moment == TimeContext.Moment.SOIR || moment == TimeContext.Moment.NUIT
        // Le soir et la nuit, on reste sombre quoi que dise le capteur :
        // allumer un plafonnier à 23h ne doit pas rallumer un écran blanc.
        if (nightHours) return DARK
        val lux = ambientLux ?: return LIGHT
        val dark = if (currentlyDark) lux < BRIGHT_ROOM_LUX else lux < DIM_ROOM_LUX
        return if (dark) DARK else LIGHT
    }

    /**
     * Suit la luminosité de la pièce et prévient à chaque fois que la palette
     * qui en découle change — pas à chaque mesure : le capteur émet plusieurs
     * fois par seconde, et réappliquer des couleurs identiques ferait
     * travailler l'écran pour rien.
     *
     * À démarrer dans onResume et arrêter dans onPause : un capteur laissé
     * enregistré consomme de la batterie en continu sur un appareil branché
     * 24h/24 mais dont l'écran passe l'essentiel du temps éteint.
     */
    class Monitor(context: Context, private val onPalette: (Palette) -> Unit) : SensorEventListener {

        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        private var lastLux: Float? = null
        private var current: Palette? = null

        fun start() {
            // Le rythme le plus lent proposé par Android : la luminosité d'une
            // pièce évolue en minutes, pas en millisecondes.
            lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            // Sans capteur, la palette doit quand même être appliquée une fois
            // (et réévaluée à chaque appel de refresh, ex. changement d'heure).
            refresh()
        }

        fun stop() {
            sensorManager?.unregisterListener(this)
        }

        /** À rappeler quand l'heure a pu changer de tranche (voir MainActivity, tic du quart d'heure). */
        fun refresh() {
            val hour = java.time.LocalDateTime.now().hour
            val palette = paletteFor(hour, lastLux, current?.isDark ?: false)
            if (palette == current) return
            current = palette
            onPalette(palette)
        }

        override fun onSensorChanged(event: SensorEvent) {
            lastLux = event.values.firstOrNull() ?: return
            refresh()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** En dessous : pièce sombre (une chambre éclairée par une lampe de chevet tourne autour de 20-50 lux). */
    private const val DIM_ROOM_LUX = 40f

    /** Au-dessus : pièce franchement éclairée (un séjour en plein jour dépasse largement 200 lux). */
    private const val BRIGHT_ROOM_LUX = 120f
}
