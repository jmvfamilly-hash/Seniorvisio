package com.seniorvisio.core

import android.content.Context
import android.content.SharedPreferences
import com.seniorvisio.BuildConfig
import org.json.JSONObject

/**
 * Source unique de vérité pour les réglages modifiables sans recompilation.
 * Stocké en local (SharedPreferences) pour la V1 ; prévu pour être remplacé
 * plus tard par une synchronisation distante (petit dashboard web) sans
 * changer les appels dans le reste de l'app - c'est pour ça que tout passe
 * par cette classe et jamais par des accès directs aux préférences ailleurs.
 */
class AdminConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("senior_visio_admin", Context.MODE_PRIVATE)

    // --- Fonction "alerte écran + décrochage présence", désactivable par l'admin ---
    var visualAlertModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUAL_ALERT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VISUAL_ALERT_ENABLED, value).apply()

    // --- Mode nuit : silencieux par défaut tant que non désactivé ---
    var nightModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_MODE_ENABLED, value).apply()

    // --- Plage horaire nuit (pour bascule silencieux automatique par défaut) ---
    var nightStartHour: Int
        get() = prefs.getInt(KEY_NIGHT_START_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_NIGHT_START_HOUR, value).apply()

    var nightEndHour: Int
        get() = prefs.getInt(KEY_NIGHT_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_NIGHT_END_HOUR, value).apply()

    // --- Durée de l'alerte avant connexion automatique (paramétrable, 30s par défaut) ---
    var countdownSeconds: Int
        get() = prefs.getInt(KEY_COUNTDOWN_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply()

    // --- Permet de désactiver totalement le blocage (appel toujours accepté après le délai) ---
    var blockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, value).apply()

    // --- PIN d'accès au panneau admin ---
    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, "0000") ?: "0000"
        set(value) = prefs.edit().putString(KEY_ADMIN_PIN, value).apply()

    // --- Clé API AssemblyAI, utilisée uniquement par le labo de comparaison
    // de transcription (voir TranscriptionLabActivity) — jamais par les
    // sous-titres de la pièce ou d'appel en usage normal. Si l'admin n'a rien
    // saisi sur la tablette, on retombe sur celle injectée par la CI depuis
    // le secret GitHub ASSEMBLYAI_API_KEY (voir build.gradle). ---
    var assemblyAiApiKey: String
        get() = prefs.getString(KEY_ASSEMBLYAI_API_KEY, "")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ASSEMBLYAI_API_KEY_DEFAULT
        set(value) = prefs.edit().putString(KEY_ASSEMBLYAI_API_KEY, value).apply()

    // --- Taille du texte des sous-titres de la pièce (voir MainActivity),
    // ajustable directement par Jean (boutons "A-"/"A+") faute de proche à
    // distance pour le faire, contrairement aux sous-titres d'appel (voir
    // WebRtcCallEngine.listenForCaptionTextSize). Mémorisée d'une session à
    // l'autre. ---
    var roomCaptionTextSizeSp: Float
        get() = prefs.getFloat(KEY_ROOM_CAPTION_TEXT_SIZE, ROOM_CAPTION_TEXT_SIZE_DEFAULT_SP)
        set(value) = prefs.edit().putFloat(KEY_ROOM_CAPTION_TEXT_SIZE, value).apply()

    fun isCurrentlyNightWindow(hourNow: Int): Boolean {
        return if (nightStartHour <= nightEndHour) {
            hourNow in nightStartHour until nightEndHour
        } else {
            // plage à cheval sur minuit (ex: 22h -> 7h)
            hourNow >= nightStartHour || hourNow < nightEndHour
        }
    }

    /** Export pratique pour debug/logs à distance. */
    fun toDebugJson(): String = JSONObject().apply {
        put("visualAlertModeEnabled", visualAlertModeEnabled)
        put("nightModeEnabled", nightModeEnabled)
        put("nightStartHour", nightStartHour)
        put("nightEndHour", nightEndHour)
        put("countdownSeconds", countdownSeconds)
        put("blockingEnabled", blockingEnabled)
    }.toString()

    companion object {
        private const val KEY_VISUAL_ALERT_ENABLED = "visual_alert_enabled"
        private const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"
        private const val KEY_NIGHT_START_HOUR = "night_start_hour"
        private const val KEY_NIGHT_END_HOUR = "night_end_hour"
        private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"
        private const val KEY_ROOM_CAPTION_TEXT_SIZE = "room_caption_text_size_sp"

        const val ROOM_CAPTION_TEXT_SIZE_DEFAULT_SP = 32f
        const val ROOM_CAPTION_TEXT_SIZE_MIN_SP = 20f
        const val ROOM_CAPTION_TEXT_SIZE_MAX_SP = 60f
        const val ROOM_CAPTION_TEXT_SIZE_STEP_SP = 4f
    }
}
