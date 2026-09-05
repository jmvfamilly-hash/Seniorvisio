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
 * (réseau Wi-Fi, sans GPS) suffit largement pour une météo par ville.
 *
 * Source : OpenWeatherMap (gratuit jusqu'à 1000 appels/jour, largement
 * suffisant vu le cache ci-dessous). Clé API configurée depuis les réglages
 * admin (voir AdminConfig.weatherApiKey) — absente par défaut : la fonction
 * reste silencieuse (aucune icône) tant que personne ne l'a renseignée,
 * plutôt que d'afficher une erreur.
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

        val apiKey = AdminConfig(appContext).weatherApiKey
        if (apiKey.isBlank()) {
            // Non configurée : on ne dérange pas avec une erreur, on affiche
            // simplement ce qu'on a déjà (rien, la première fois).
            onResult(cached)
            return
        }

        val location = currentLocation()
        if (location == null) {
            // Pas encore de position connue (juste après l'installation, ou
            // service de localisation coupé) : on déclenche une recherche en
            // arrière-plan pour le prochain appel, sans bloquer celui-ci.
            requestLocationOnce()
            onResult(cached)
            return
        }

        Thread {
            val fresh = try {
                fetchFromNetwork(apiKey, location.latitude, location.longitude)
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

    private fun fetchFromNetwork(apiKey: String, lat: Double, lon: Double): Weather {
        val url = URL(
            "https://api.openweathermap.org/data/2.5/weather" +
                "?lat=$lat&lon=$lon&appid=$apiKey&lang=fr&units=metric"
        )
        val connection = url.openConnection() as HttpURLConnection
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
        val main = JSONObject(body).getJSONArray("weather").getJSONObject(0).getString("main")
        return toWeather(main)
    }

    /**
     * Réduit les catégories d'OpenWeatherMap (une vingtaine, très techniques :
     * "Drizzle", "Squall", "Ash"...) à quatre pictogrammes/mots simples —
     * l'exhaustivité ne sert à rien ici, la nuance entre "Rain" et "Drizzle"
     * n'aide personne à décider s'il faut un parapluie.
     */
    private fun toWeather(owmMain: String): Weather = when (owmMain) {
        "Clear" -> Weather("☀️", "Beau")
        "Clouds" -> Weather("☁️", "Nuageux")
        "Rain", "Drizzle" -> Weather("🌧️", "Pluie")
        "Thunderstorm" -> Weather("⛈️", "Orage")
        "Snow" -> Weather("❄️", "Neige")
        else -> Weather("🌫️", "Brumeux")
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
