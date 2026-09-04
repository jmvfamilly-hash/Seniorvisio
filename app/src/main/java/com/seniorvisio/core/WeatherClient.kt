package com.seniorvisio.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Météo du jour, réduite au strict minimum recommandé pour un affichage
 * senior : un pictogramme et un mot ("☀️ Beau"), jamais de température ni de
 * détail (humidité, vent...) — chaque donnée en plus est une question en plus
 * ("Il fait combien ?") sans bénéfice réel ici.
 *
 * Source : OpenWeatherMap (gratuit jusqu'à 1000 appels/jour, largement
 * suffisant vu le cache ci-dessous). Clé API et ville configurées depuis les
 * réglages admin (voir AdminConfig.weatherApiKey/weatherLocation) — absentes
 * par défaut : la fonction reste silencieuse (aucune icône) tant que
 * personne ne les a renseignées, plutôt que d'afficher une erreur.
 *
 * Résultat mis en cache une heure : la météo ne change pas assez vite pour
 * justifier un appel réseau à chaque réveil d'écran, et un appel manqué
 * (Wi-Fi coupé un instant) retombe sur la dernière valeur connue plutôt que
 * de laisser l'écran vide.
 */
class WeatherClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

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

        val adminConfig = AdminConfig(appContext)
        val apiKey = adminConfig.weatherApiKey
        val location = adminConfig.weatherLocation
        if (apiKey.isBlank() || location.isBlank()) {
            // Non configuré : on ne dérange pas avec une erreur, on affiche
            // simplement ce qu'on a déjà (rien, la première fois).
            onResult(cached)
            return
        }

        Thread {
            val fresh = try {
                fetchFromNetwork(apiKey, location)
            } catch (e: Exception) {
                Log.w(TAG, "Rafraîchissement météo impossible, on garde la dernière valeur connue", e)
                null
            }
            if (fresh != null) writeCache(fresh)
            mainHandler.post { onResult(fresh ?: cached) }
        }.start()
    }

    private fun fetchFromNetwork(apiKey: String, location: String): Weather {
        val encodedLocation = URLEncoder.encode(location, "UTF-8")
        val url = URL(
            "https://api.openweathermap.org/data/2.5/weather" +
                "?q=$encodedLocation&appid=$apiKey&lang=fr&units=metric"
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
