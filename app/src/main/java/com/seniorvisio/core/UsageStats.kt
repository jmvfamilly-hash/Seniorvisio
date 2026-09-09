package com.seniorvisio.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ce que la tablette a réellement fait de sa journée : temps de transcription
 * par moteur, appels reçus, heures d'éveil et de sommeil de l'écran.
 *
 * Deux besoins, tous deux hors de portée jusqu'ici. D'abord la facture : le
 * partage entre moteur embarqué et service payant ne se décide sérieusement
 * qu'en sachant ce que le second aurait coûté s'il avait tout fait — d'où deux
 * compteurs distincts, le temps réellement passé sur AssemblyAI et le temps
 * qu'il aurait facturé pour la même écoute. Ensuite la vie de Jean : savoir à
 * quelles heures l'écran s'allume, c'est savoir à quelles heures il y a
 * quelqu'un et de l'activité dans la pièce.
 *
 * Une journée est découpée en tranches de quart d'heure, ce qui permet à la
 * fois le graphique d'une journée et la moyenne d'une semaine — un total
 * quotidien ne dirait ni l'un ni l'autre. Huit jours conservés : sept pour la
 * moyenne, plus celui en cours.
 *
 * Le temps n'est jamais crédité au moment où l'état change, mais rattrapé
 * périodiquement depuis le dernier passage (voir [flush]). Une tablette peut
 * être coupée, redémarrée, ou son processus tué à tout moment : compter au
 * changement d'état perdrait tout ce qui précède le dernier changement, c'est-
 * à-dire précisément les longues plages de sommeil qu'on cherche à mesurer.
 */
object UsageStats {

    /** Un moteur, tel qu'il apparaît dans les compteurs. */
    const val ENGINE_ASSEMBLYAI = "assemblyai"
    const val ENGINE_VOSK = "vosk"
    const val ENGINE_ANDROID = "android"

    /**
     * Le nom de compteur d'un moteur donné.
     *
     * Existe pour qu'un moteur ajouté plus tard ne se retrouve pas compté sous
     * le nom d'un autre : la correspondance se faisait par un « si Vosk, sinon
     * AssemblyAI », qui range silencieusement tout nouveau venu du côté
     * facturé. AUTO ne parvient jamais jusqu'ici — il est résolu en un moteur
     * réel avant toute ouverture de session (voir TranscriptionEngine) — mais
     * le cas est traité plutôt que laissé au hasard d'une exception.
     */
    fun engineFor(choice: TranscriptionEngineChoice): String = when (choice) {
        TranscriptionEngineChoice.ASSEMBLYAI -> ENGINE_ASSEMBLYAI
        TranscriptionEngineChoice.VOSK -> ENGINE_VOSK
        TranscriptionEngineChoice.ANDROID -> ENGINE_ANDROID
        TranscriptionEngineChoice.AUTO -> ENGINE_VOSK
    }

    private lateinit var prefs: SharedPreferences

    @Volatile private var screenOn = false
    @Volatile private var transcribingEngine: String? = null

    /** Instant du dernier rattrapage, en millisecondes depuis l'époque. */
    private var lastFlushAtMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("senior_visio_usage", Context.MODE_PRIVATE)
        lastFlushAtMs = prefs.getLong(KEY_LAST_FLUSH, 0L)
        screenOn = prefs.getBoolean(KEY_SCREEN_ON, false)
        transcribingEngine = prefs.getString(KEY_ENGINE, null)
    }

    private fun ready() = ::prefs.isInitialized

    // ---- Événements ----

    @Synchronized
    fun noteScreenState(on: Boolean) {
        if (!ready() || screenOn == on) return
        flushLocked(System.currentTimeMillis())
        screenOn = on
        prefs.edit().putBoolean(KEY_SCREEN_ON, on).apply()
    }

    /**
     * Une session de reconnaissance vient de s'ouvrir. C'est bien la session
     * qui compte et non la parole : AssemblyAI facture la durée de connexion,
     * pas le nombre de mots.
     */
    @Synchronized
    fun noteTranscriptionStart(engine: String) {
        if (!ready() || transcribingEngine == engine) return
        flushLocked(System.currentTimeMillis())
        transcribingEngine = engine
        prefs.edit().putString(KEY_ENGINE, engine).apply()
    }

    @Synchronized
    fun noteTranscriptionStop() {
        if (!ready() || transcribingEngine == null) return
        flushLocked(System.currentTimeMillis())
        transcribingEngine = null
        prefs.edit().remove(KEY_ENGINE).apply()
    }

    /** Un appel qui s'est réellement tenu, avec l'heure et la durée. */
    @Synchronized
    fun noteCall(startedAtMs: Long, durationSeconds: Int) {
        if (!ready()) return
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAtMs), ZoneId.systemDefault())
        val day = readDay(start.toLocalDate().toString())
        val calls = day.optJSONArray(FIELD_CALLS) ?: JSONArray()
        calls.put(
            JSONObject()
                .put("h", start.hour)
                .put("m", start.minute)
                .put("d", durationSeconds.coerceAtLeast(0))
        )
        day.put(FIELD_CALLS, calls)
        writeDay(start.toLocalDate().toString(), day)
    }

    // ---- Rattrapage du temps écoulé ----

    /**
     * Crédite le temps écoulé depuis le dernier passage aux bons compteurs et
     * aux bonnes tranches. À appeler régulièrement (voir CallListenerService,
     * qui bat déjà toutes les cinq minutes) et à chaque changement d'état.
     */
    @Synchronized
    fun flush() {
        if (!ready()) return
        flushLocked(System.currentTimeMillis())
    }

    private fun flushLocked(nowMs: Long) {
        val from = lastFlushAtMs
        lastFlushAtMs = nowMs
        prefs.edit().putLong(KEY_LAST_FLUSH, nowMs).apply()

        // Premier passage, ou horloge reculée (changement d'heure, remise à
        // l'heure réseau après un démarrage) : on repart de maintenant plutôt
        // que de créditer un intervalle absurde.
        if (from <= 0L || nowMs <= from) return
        // Un écart énorme veut dire tablette éteinte ou processus tué pendant
        // tout ce temps : ce n'est ni de l'éveil ni du sommeil observé, et le
        // compter fausserait la moyenne de la semaine.
        if (nowMs - from > MAX_CATCH_UP_MS) return

        var cursor = from
        while (cursor < nowMs) {
            val moment = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(cursor), ZoneId.systemDefault())
            val slot = moment.hour * SLOTS_PER_HOUR + moment.minute / SLOT_MINUTES
            // Fin de la tranche courante, ou fin de l'intervalle : on ne
            // crédite jamais à cheval sur deux tranches, sinon le graphique de
            // la journée décalerait un peu plus à chaque passage.
            val slotEnd = moment
                .withMinute((slot % SLOTS_PER_HOUR) * SLOT_MINUTES)
                .withSecond(0).withNano(0)
                .plusMinutes(SLOT_MINUTES.toLong())
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val chunkEnd = minOf(nowMs, slotEnd)
            val seconds = ((chunkEnd - cursor) / 1000L).toInt()
            if (seconds > 0) credit(moment.toLocalDate(), slot, seconds)
            cursor = chunkEnd
        }
    }

    private fun credit(date: LocalDate, slot: Int, seconds: Int) {
        val key = date.toString()
        val day = readDay(key)

        if (screenOn) {
            day.put(FIELD_AWAKE, day.optInt(FIELD_AWAKE) + seconds)
            val slots = day.optJSONArray(FIELD_SLOTS) ?: emptySlots()
            slots.put(slot, slots.optInt(slot) + seconds)
            day.put(FIELD_SLOTS, slots)
        } else {
            day.put(FIELD_ASLEEP, day.optInt(FIELD_ASLEEP) + seconds)
        }

        transcribingEngine?.let { engine ->
            val engines = day.optJSONObject(FIELD_ENGINES) ?: JSONObject()
            engines.put(engine, engines.optInt(engine) + seconds)
            day.put(FIELD_ENGINES, engines)
            // Ce qu'AssemblyAI aurait facturé s'il avait tout transcrit :
            // toutes les secondes de session, quel que soit le moteur qui les a
            // réellement assurées. C'est la seule mesure qui permette de dire
            // ce que le moteur embarqué fait économiser.
            day.put(FIELD_BILLABLE_EQUIVALENT, day.optInt(FIELD_BILLABLE_EQUIVALENT) + seconds)
        }

        writeDay(key, day)
    }

    // ---- Lecture ----

    /** Le jour demandé au format ISO (2026-09-08), prêt à être publié. */
    @Synchronized
    fun dayAsJson(date: LocalDate): JSONObject {
        if (!ready()) return JSONObject()
        return readDay(date.toString()).put("date", date.toString())
    }

    @Synchronized
    fun today(): JSONObject = dayAsJson(LocalDate.now())

    /**
     * Supprime les journées trop anciennes. Appelé au même rythme que la
     * publication : sans ça, les préférences grossiraient indéfiniment sur une
     * tablette qui tourne des années.
     */
    @Synchronized
    fun pruneOldDays() {
        if (!ready()) return
        val keep = (0 until KEEP_DAYS).map { KEY_DAY_PREFIX + LocalDate.now().minusDays(it.toLong()) }.toSet()
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_DAY_PREFIX) && it !in keep }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun readDay(key: String): JSONObject = try {
        prefs.getString(KEY_DAY_PREFIX + key, null)?.let { JSONObject(it) } ?: JSONObject()
    } catch (e: Exception) {
        // Journée illisible (écriture interrompue par une coupure) : on repart
        // d'une journée vide plutôt que de perdre toutes les suivantes.
        JSONObject()
    }

    private fun writeDay(key: String, day: JSONObject) {
        prefs.edit().putString(KEY_DAY_PREFIX + key, day.toString()).apply()
    }

    private fun emptySlots(): JSONArray {
        val slots = JSONArray()
        repeat(SLOTS_PER_DAY) { slots.put(0) }
        return slots
    }

    const val SLOT_MINUTES = 15
    const val SLOTS_PER_HOUR = 60 / SLOT_MINUTES
    const val SLOTS_PER_DAY = 24 * SLOTS_PER_HOUR

    /** Sept jours pour la moyenne, plus celui en cours. */
    const val KEEP_DAYS = 8

    const val FIELD_AWAKE = "awakeSeconds"
    const val FIELD_ASLEEP = "asleepSeconds"
    const val FIELD_SLOTS = "awakeSlots"
    const val FIELD_ENGINES = "engineSeconds"
    const val FIELD_BILLABLE_EQUIVALENT = "billableEquivalentSeconds"
    const val FIELD_CALLS = "calls"

    private const val KEY_DAY_PREFIX = "day_"
    private const val KEY_LAST_FLUSH = "last_flush_at"
    private const val KEY_SCREEN_ON = "screen_on"
    private const val KEY_ENGINE = "engine"

    /** Au-delà, l'écart traduit une tablette éteinte, pas du sommeil observé. */
    private const val MAX_CATCH_UP_MS = 30 * 60 * 1000L
}
