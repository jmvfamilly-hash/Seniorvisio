package com.seniorvisio.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Météo du jour, réduite au strict minimum recommandé pour un affichage
 * senior : un pictogramme et un mot ("☀️ Beau"), jamais de température ni de
 * détail (humidité, vent...) — chaque donnée en plus est une question en plus
 * ("Il fait combien ?") sans bénéfice réel ici.
 *
 * Position obtenue par géolocalisation de la tablette (voir [currentLocation])
 * plutôt qu'une ville saisie à la main : rien à configurer ni à corriger si la
 * tablette change de pièce ou de logement. Une position approximative
 * (réseau Wi-Fi, sans GPS) suffit largement pour une météo par ville. Sans
 * position connue, wttr.in devine lui-même la région à partir de l'adresse IP
 * — approximatif mais suffisant pour un pictogramme, et surtout jamais un
 * écran vide.
 *
 * Source : wttr.in, qui ne demande aucune clé API — un compte à créer, une clé
 * à saisir dans le panneau admin et une clé qui expire un jour sans prévenir
 * étaient trois occasions de panne pour une information d'appoint. Le service
 * renvoie les codes météo WWO, dont la liste est fermée et connue : chacun des
 * 48 codes possibles a donc ici son pictogramme et son libellé français, sans
 * catégorie de repli approximative.
 *
 * Résultat mis en cache une heure : la météo ne change pas assez vite pour
 * justifier un appel réseau à chaque réveil d'écran, et un appel manqué
 * (Wi-Fi coupé un instant, position pas encore connue) retombe sur la
 * dernière valeur connue plutôt que de laisser l'écran vide.
 */
class WeatherClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy {
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    data class Weather(val icon: String, val label: String)

    /**
     * Renvoie la météo en cache immédiatement si elle a moins d'une heure,
     * sinon la rafraîchit en tâche de fond puis rappelle `onResult` (sur le
     * thread principal) — avec la valeur fraîche, ou l'ancienne si le
     * rafraîchissement échoue, ou `null` si aucune n'a jamais été obtenue.
     */
    fun fetchWeather(onResult: (Weather?) -> Unit) {
        val cached = readCache()
        val cacheAgeMs = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L)
        if (cached != null && cacheAgeMs < CACHE_MAX_AGE_MS) {
            onResult(cached)
            return
        }

        val location = currentLocation()
        if (location == null) {
            // Pas encore de position connue (juste après l'installation, ou
            // service de localisation coupé) : on en demande une pour la
            // prochaine fois, sans renoncer à la météo pour autant — wttr.in
            // sait localiser approximativement par adresse IP.
            requestLocationOnce()
        }

        Thread {
            val fresh = try {
                fetchFromNetwork(location)
            } catch (e: Exception) {
                Log.w(TAG, "Rafraîchissement météo impossible, on garde la dernière valeur connue", e)
                null
            }
            if (fresh != null) writeCache(fresh)
            mainHandler.post { onResult(fresh ?: cached) }
        }.start()
    }

    /**
     * Dernière position connue, la plus récente parmi les fournisseurs
     * disponibles. Le réseau (Wi-Fi/cellulaire) suffit très largement pour une
     * météo par ville et fonctionne en intérieur, contrairement au GPS —
     * demandé en priorité, le GPS n'est qu'un repli si jamais disponible.
     */
    private fun currentLocation(): Location? {
        val manager = locationManager ?: return null
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                try {
                    if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
                } catch (_: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    /**
     * Aucune position en cache n'existe tant qu'aucune appli (dont celle-ci)
     * n'en a jamais demandé une sur cet appareil — typique juste après
     * l'installation. Une demande ponctuelle suffit à en obtenir une, qui
     * restera ensuite disponible via getLastKnownLocation pour tous les
     * appels suivants, sans avoir à réécouter en continu.
     */
    private fun requestLocationOnce() {
        val manager = locationManager ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        try {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, {}, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Demande de position refusée", e)
        }
    }

    /**
     * `format=j1` est la sortie JSON de wttr.in (la sortie par défaut est un
     * dessin ASCII destiné à un terminal). L'en-tête User-Agent est
     * obligatoire : sans lui, le service renvoie justement ce dessin ASCII au
     * lieu du JSON, en supposant un appel depuis curl.
     */
    private fun fetchFromNetwork(location: Location?): Weather {
        val place = location?.let { "${it.latitude},${it.longitude}" } ?: ""
        val url = URL("https://wttr.in/$place?format=j1")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "SeniorVisio/1.0")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw java.io.IOException("Réponse météo inattendue (code HTTP $code)")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val current = JSONObject(body).getJSONArray("current_condition").getJSONObject(0)
        return toWeather(current.getString("weatherCode").toIntOrNull() ?: -1)
    }

    /**
     * Les codes WWO renvoyés par wttr.in forment une liste fermée de 48
     * valeurs : chacune a donc ici son pictogramme et son libellé, plutôt
     * qu'un repli fourre-tout. Les libellés restent volontairement des mots
     * simples et courts — "Bruine verglaçante forte" tient sur l'écran et se
     * lit d'un coup d'œil, la formulation officielle "Heavy freezing drizzle"
     * traduite littéralement, non.
     */
    private fun toWeather(weatherCode: Int): Weather = when (weatherCode) {
        113 -> Weather("☀️", "Beau")
        116 -> Weather("🌤️", "Éclaircies")
        119 -> Weather("☁️", "Nuageux")
        122 -> Weather("☁️", "Couvert")
        143 -> Weather("🌫️", "Brume")
        176 -> Weather("🌦️", "Pluie possible")
        179 -> Weather("🌨️", "Neige possible")
        182 -> Weather("🌨️", "Neige fondue possible")
        185 -> Weather("🌧️", "Bruine verglaçante possible")
        200 -> Weather("⛈️", "Orage possible")
        227 -> Weather("🌬️", "Neige soufflée")
        230 -> Weather("❄️", "Blizzard")
        248 -> Weather("🌫️", "Brouillard")
        260 -> Weather("🌫️", "Brouillard givrant")
        263 -> Weather("🌦️", "Bruine légère")
        266 -> Weather("🌦️", "Bruine")
        281 -> Weather("🌧️", "Bruine verglaçante")
        284 -> Weather("🌧️", "Bruine verglaçante forte")
        293 -> Weather("🌦️", "Pluie légère")
        296 -> Weather("🌧️", "Pluie légère")
        299 -> Weather("🌧️", "Averses modérées")
        302 -> Weather("🌧️", "Pluie")
        305 -> Weather("🌧️", "Fortes averses")
        308 -> Weather("🌧️", "Forte pluie")
        311 -> Weather("🌧️", "Pluie verglaçante")
        314 -> Weather("🌧️", "Forte pluie verglaçante")
        317 -> Weather("🌨️", "Neige fondue")
        320 -> Weather("🌨️", "Forte neige fondue")
        323 -> Weather("🌨️", "Neige légère")
        326 -> Weather("🌨️", "Neige légère")
        329 -> Weather("❄️", "Neige")
        332 -> Weather("❄️", "Neige")
        335 -> Weather("❄️", "Forte neige")
        338 -> Weather("❄️", "Forte neige")
        350 -> Weather("🧊", "Grésil")
        353 -> Weather("🌦️", "Averse légère")
        356 -> Weather("🌧️", "Forte averse")
        359 -> Weather("🌧️", "Pluie torrentielle")
        362 -> Weather("🌨️", "Averse de neige fondue")
        365 -> Weather("🌨️", "Forte averse de neige fondue")
        368 -> Weather("🌨️", "Averse de neige")
        371 -> Weather("❄️", "Forte averse de neige")
        374 -> Weather("🧊", "Averse de grésil")
        377 -> Weather("🧊", "Forte averse de grésil")
        386 -> Weather("⛈️", "Orage")
        389 -> Weather("⛈️", "Fort orage")
        392 -> Weather("⛈️", "Orage de neige")
        395 -> Weather("⛈️", "Fort orage de neige")
        // Code inconnu (nouveau code ajouté par le service, réponse
        // inattendue) : on n'affiche rien plutôt qu'un pictogramme faux.
        else -> {
            Log.w(TAG, "Code météo inconnu : $weatherCode")
            Weather("🌡️", "Météo")
        }
    }

    private fun readCache(): Weather? {
        val icon = prefs.getString(KEY_ICON, null) ?: return null
        val label = prefs.getString(KEY_LABEL, null) ?: return null
        return Weather(icon, label)
    }

    private fun writeCache(weather: Weather) {
        prefs.edit()
            .putString(KEY_ICON, weather.icon)
            .putString(KEY_LABEL, weather.label)
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val TAG = "WeatherClient"
        private const val PREFS_NAME = "senior_visio_weather"
        private const val KEY_ICON = "icon"
        private const val KEY_LABEL = "label"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L
    }
}
