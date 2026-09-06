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

    // --- Blocage du réveil de l'écran pendant la nuit (voir
    // RoomPresenceService.ensureAwake). Désactivé par défaut : une chambre où
    // l'on parle à trois heures du matin est justement le moment où Jean a le
    // plus besoin de lire ce qui se dit — un soignant qui entre, quelqu'un qui
    // l'appelle. Bloquer par défaut revenait à éteindre la fonction
    // précisément quand elle sert le plus, et de façon invisible : rien à
    // l'écran ne disait que c'était l'heure qui l'empêchait.
    //
    // À activer sur place si la lumière de la dalle finit par gêner le
    // sommeil — ce qui dépend de la pièce et de la personne, pas d'une règle
    // générale. Clé distincte de l'ancienne : le sens du réglage s'inverse,
    // une valeur enregistrée sous l'ancien nom voudrait dire le contraire. ---
    var blockWakeAtNight: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_WAKE_AT_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_WAKE_AT_NIGHT, value).apply()

    // --- Plage horaire considérée comme la nuit (voir blockWakeAtNight) ---
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

    // --- Clé API AssemblyAI : utilisée par le labo de comparaison de
    // transcription (voir TranscriptionLabActivity) ET par la transcription
    // temps réel des sous-titres d'appel (voir WebRtcCallEngine.
    // attachTranscriptionSink, AssemblyAiRealtimeTranscriber). Si l'admin n'a
    // rien saisi sur la tablette, on retombe sur celle injectée par la CI
    // depuis le secret GitHub ASSEMBLYAI_API_KEY (voir build.gradle). ---
    var assemblyAiApiKey: String
        get() = prefs.getString(KEY_ASSEMBLYAI_API_KEY, "")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ASSEMBLYAI_API_KEY_DEFAULT
        set(value) = prefs.edit().putString(KEY_ASSEMBLYAI_API_KEY, value).apply()

    // --- Ordre d'empilement des trois zones de l'écran de Jean (voir
    // HomeZonesController) : de haut en bas. Stocké comme la liste des zones
    // séparées par des virgules plutôt qu'un simple numéro de permutation, pour
    // rester lisible dans les préférences et survivre à l'ajout d'une
    // quatrième zone. Toute valeur incomplète ou abîmée (zone inconnue, zone
    // manquante, doublon) retombe sur l'ordre par défaut plutôt que d'amputer
    // l'écran de Jean d'une zone. ---
    var zoneOrder: List<HomeZone>
        get() {
            val stored = prefs.getString(KEY_ZONE_ORDER, null)
                ?.split(',')
                ?.mapNotNull { name -> HomeZone.entries.firstOrNull { it.name == name } }
                ?: return HomeZone.DEFAULT_ORDER
            return if (stored.toSet() == HomeZone.entries.toSet()) stored else HomeZone.DEFAULT_ORDER
        }
        set(value) = prefs.edit().putString(KEY_ZONE_ORDER, value.joinToString(",") { it.name }).apply()

    // --- Moteur de reconnaissance vocale, réglable séparément par source et
    // modifiable à distance en cours de route (voir DeviceStatusReporter).
    // AUTO applique le partage par défaut : la pièce sur le moteur embarqué,
    // gratuit, parce qu'elle est écoutée des heures par jour ; les appels sur
    // AssemblyAI, ponctuels et où la justesse du texte se voit le plus. Les
    // forcer l'un ou l'autre sert surtout à les comparer sur la même voix
    // dans la même pièce, ce qu'aucun avis a priori ne remplace. ---
    var roomEngine: TranscriptionEngineChoice
        get() = TranscriptionEngineChoice.fromRemoteValue(prefs.getString(KEY_ROOM_ENGINE, null))
            ?: TranscriptionEngineChoice.AUTO
        set(value) = prefs.edit().putString(KEY_ROOM_ENGINE, value.remoteValue).apply()

    var callEngine: TranscriptionEngineChoice
        get() = TranscriptionEngineChoice.fromRemoteValue(prefs.getString(KEY_CALL_ENGINE, null))
            ?: TranscriptionEngineChoice.AUTO
        set(value) = prefs.edit().putString(KEY_CALL_ENGINE, value.remoteValue).apply()

    // --- Taille du modèle embarqué (voir VoskModelSize). Le grand par défaut :
    // le petit s'est révélé conçu pour de la commande vocale plus que pour une
    // conversation captée à deux mètres, ce qui est précisément l'usage ici. ---
    var voskModelSize: VoskModelSize
        get() = VoskModelSize.fromRemoteValue(prefs.getString(KEY_VOSK_MODEL_SIZE, null))
            ?: VoskModelSize.LARGE
        set(value) = prefs.edit().putString(KEY_VOSK_MODEL_SIZE, value.remoteValue).apply()

    // --- Réveil de l'écran au moindre son de la pièce (voir RoomPresenceService) ---
    var roomWakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOM_WAKE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ROOM_WAKE_ENABLED, value).apply()

    // --- Seuil de déclenchement (RMS, échelle 0-32767) : plus petit = plus
    // sensible. Dépend du microphone et de l'acoustique de la pièce, à
    // ajuster sur place plutôt qu'une valeur unique valable partout. ---
    var roomWakeSensitivityThreshold: Int
        get() = prefs.getInt(KEY_ROOM_WAKE_THRESHOLD, 1000)
        set(value) = prefs.edit().putInt(KEY_ROOM_WAKE_THRESHOLD, value).apply()

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
        put("blockWakeAtNight", blockWakeAtNight)
        put("nightStartHour", nightStartHour)
        put("nightEndHour", nightEndHour)
        put("countdownSeconds", countdownSeconds)
        put("blockingEnabled", blockingEnabled)
    }.toString()

    companion object {
        private const val KEY_VISUAL_ALERT_ENABLED = "visual_alert_enabled"
        private const val KEY_BLOCK_WAKE_AT_NIGHT = "block_wake_at_night"
        private const val KEY_NIGHT_START_HOUR = "night_start_hour"
        private const val KEY_NIGHT_END_HOUR = "night_end_hour"
        private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"
        private const val KEY_ZONE_ORDER = "zone_order"
        private const val KEY_ROOM_ENGINE = "room_engine"
        private const val KEY_CALL_ENGINE = "call_engine"
        private const val KEY_VOSK_MODEL_SIZE = "vosk_model_size"
        private const val KEY_ROOM_WAKE_ENABLED = "room_wake_enabled"
        private const val KEY_ROOM_WAKE_THRESHOLD = "room_wake_threshold"
    }
}
