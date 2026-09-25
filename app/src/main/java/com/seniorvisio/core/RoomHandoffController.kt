package com.seniorvisio.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.seniorvisio.ui.MainActivity
import com.seniorvisio.ui.ReturnBannerOverlay
import java.time.LocalDateTime

/**
 * Bascule l'écran de Jean vers « Transcription instantanée » de Google dès
 * qu'une voix se fait entendre dans la pièce, et l'en ramène.
 *
 * ═══ Pourquoi déléguer, alors qu'on transcrit déjà ═══
 *
 * L'interface publique de reconnaissance vocale d'Android est **modale** : elle
 * transcrit un énoncé, s'arrête, et doit être relancée. Ce temps mort est
 * exactement ce qui coupe les phrases et mange les premiers mots. Sur les
 * moteurs qu'on alimente nous-mêmes, le défaut a été corrigé par une réserve de
 * son rejouée à l'ouverture — impossible ici, puisque ce moteur tient le micro
 * et ne nous laisse jamais voir le son. Google, dans sa propre application,
 * n'est pas soumis à cette interface et transcrit en continu.
 *
 * Ce mode consiste donc à céder l'écran plutôt qu'à concurrencer ce qu'on ne
 * peut pas égaler.
 *
 * ═══ Le microphone, encore ═══
 *
 * Un seul composant à la fois le tient. En basculant, il faut donc **relâcher
 * volontairement** le nôtre et suspendre les réessais — faute de quoi la
 * boucle de reprise (voir RoomPresenceService.scheduleCaptureRetry, qui
 * n'abandonne jamais, par conception) le reprendrait à Transcription
 * instantanée toutes les quelques secondes et la rendrait inutilisable.
 *
 * Conséquence directe et inévitable : pendant la bascule, Senior Visio est
 * **sourd**. Il ne peut pas savoir que la pièce s'est vidée, et un retour au
 * silence — qui serait pourtant le bon critère — est hors d'atteinte. D'où
 * quatre chemins de retour, aucun fondé sur l'écoute.
 */
class RoomHandoffController(
    private val context: Context,
    private val adminConfig: AdminConfig,
    /** Relâche le micro et suspend les réessais de capture. */
    private val onRelease: () -> Unit,
    /** Reprend l'écoute normale. */
    private val onResume: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val banner = ReturnBannerOverlay(context)

    @Volatile var active = false
        private set

    /** Quand on est revenu pour la dernière fois, pour ne pas repartir aussitôt. */
    private var lastReturnAtMs = 0L

    /**
     * Dernière raison d'agir ou de ne pas agir, publiée au diagnostic.
     *
     * Sa valeur de départ dit ce qu'elle est réellement — armé, mais rien
     * entendu encore. Elle valait « inactif », ce qui se confondait mot pour
     * mot avec l'option coupée : deux situations opposées, à corriger de deux
     * façons différentes, et rigoureusement indiscernables à la lecture.
     */
    @Volatile var lastDecision: String = "armée, aucune voix entendue depuis le démarrage"
        private set

    private var handoffCount = 0

    /**
     * Combien de retours par cause, depuis le démarrage.
     *
     * C'est la mesure qui tranche une question qu'on ne peut pas trancher
     * autrement : **la tablette s'endort-elle seulement pendant que
     * Transcription instantanée est affichée ?** Cette application est faite
     * pour être lue en continu et maintient peut-être l'écran allumé, auquel
     * cas la veille ne survient jamais et seul le filet de sécurité agit. Si
     * les retours se font tous par « durée écoulée », c'est la réponse ; s'ils
     * se font par « écran éteint », le filet peut être coupé.
     *
     * Supposer aurait été facile et faux dans un cas sur deux — il s'agit du
     * comportement d'une application qu'on ne maîtrise pas, sur un appareil
     * qu'on n'a pas en main.
     */
    private val returnsByReason = linkedMapOf<String, Int>()

    /**
     * Combien de fois une voix a été entendue, et combien de fois la bascule a
     * été refusée, par cause.
     *
     * Ces deux nombres séparent trois situations que « rien ne se passe » ne
     * distingue pas, et qui se corrigent de trois façons opposées :
     *
     *  - aucune voix comptée : le déclencheur n'est jamais appelé, et c'est en
     *    amont qu'il faut chercher — capture, détection de voix ;
     *  - des voix comptées et des refus : une garde s'y oppose, et elle est
     *    nommée ;
     *  - des voix comptées et aucun refus alors que rien ne bascule : l'état
     *    est incohérent.
     *
     * Le dernier cas était invisible jusqu'ici. C'est précisément celui qui
     * produit « ça a basculé une fois puis plus jamais ».
     */
    private var voiceHeardCount = 0
    private val refusalsByReason = linkedMapOf<String, Int>()
    private var staleStateRecoveries = 0

    private val timedReturn = Runnable { returnToHomeScreen("durée écoulée") }

    /**
     * L'extinction de l'écran vaut retour : plus personne ne regarde, et Jean
     * doit retrouver son écran d'accueil au réveil suivant, pas celui d'une
     * application qu'il n'a pas ouverte.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) returnToHomeScreen("écran éteint")
        }
    }

    /**
     * Appelée à chaque fois qu'une **voix** est entendue dans la pièce — pas un
     * simple bruit.
     *
     * La distinction est décisive ici, bien plus que pour l'ouverture d'une
     * session payante : basculer l'écran de Jean est un geste visible, et un
     * aspirateur ou une porte ne doivent pas le déclencher.
     */
    fun onVoiceHeard() {
        voiceHeardCount++
        if (active) return
        val reason = refuseReason()
        if (reason != null) {
            // Compté par cause, et pas seulement retenu comme « dernière
            // décision » : une garde qui refuse cent fois et une garde qui n'a
            // jamais refusé donnent le même texte, et ce sont deux
            // diagnostics opposés.
            refusalsByReason[reason] = (refusalsByReason[reason] ?: 0) + 1
            lastDecision = reason
            return
        }
        handOff()
    }

    /**
     * Notre boucle de capture tourne : le microphone nous est donc revenu, ce
     * qui **prouve** que nous ne sommes plus basculés — une bascule commence
     * précisément par le relâcher.
     *
     * Cette preuve est gratuite et rattrape le pire mode de panne de ce
     * mécanisme : un état resté bloqué sur « basculé » alors que l'écran de
     * Jean est revenu. La première ligne de [onVoiceHeard] interdit alors toute
     * nouvelle bascule pour toujours, sans le moindre message — et le symptôme
     * est exactement « ça a basculé une fois, puis plus jamais ».
     *
     * Les quatre chemins de retour appellent bien [returnToHomeScreen], mais
     * en dépendre revenait à supposer qu'aucun autre chemin n'existe. Le
     * système en a d'autres : une application tuée pour faire de la place, un
     * retour arrière, une bascule refusée à mi-course.
     */
    fun noteMicrophoneHeld() {
        if (!active) return
        Log.w(TAG, "État incohérent : le micro est revenu alors que l'état dit « basculé »")
        staleStateRecoveries++
        active = false
        handler.removeCallbacks(timedReturn)
        banner.hide()
        unregisterScreenOff()
        lastDecision = "état réinitialisé : le micro était revenu sans retour signalé"
    }

    /**
     * Ce qui interdit la bascule à cet instant, ou null si elle est permise.
     *
     * Énumérées dans l'ordre où elles s'appliquent, et chacune nommée : sans
     * ça, un mode qui ne bascule pas ressemble à un mode en panne, et les cinq
     * causes se corrigent différemment.
     */
    private fun refuseReason(): String? {
        if (!adminConfig.roomHandoffEnabled) return "désactivée"
        if (!CompanionApps.isTranscriptionInstalled(context)) {
            return "Transcription instantanée absente de cette tablette"
        }
        // La nuit, changer l'écran est pire que de le laisser : un réveil à
        // trois heures du matin sur l'écran d'une autre application n'aide
        // personne.
        if (adminConfig.blockWakeAtNight &&
            adminConfig.isCurrentlyNightWindow(LocalDateTime.now().hour)
        ) {
            return "bascule bloquée (nuit)"
        }
        // Retour tout juste effectué : sans cette retenue, Jean revient à son
        // écran, quelqu'un prononce un mot, et il repart immédiatement. La
        // tablette deviendrait impossible à récupérer.
        val sinceReturn = System.currentTimeMillis() - lastReturnAtMs
        if (lastReturnAtMs != 0L && sinceReturn < RETURN_COOLDOWN_MS) {
            return "retour récent, pause de ${(RETURN_COOLDOWN_MS - sinceReturn) / 1000}s"
        }
        return null
    }

    /**
     * Bascule immédiatement, sans consulter aucune condition. Rend le message
     * d'échec, ou null si c'est parti.
     *
     * Réservé au bouton de test de l'écran d'administration. Il sépare deux
     * questions que le comportement normal mêle : « la bascule fonctionne-t-elle
     * sur cette tablette » et « le déclencheur part-il ». Sans ce partage, un
     * mode qui ne bascule pas a six causes possibles et rien pour les
     * départager.
     */
    fun forceHandOff(): String? {
        if (active) return "déjà basculé"
        if (!CompanionApps.isTranscriptionInstalled(context)) {
            return "Transcription instantanée introuvable sur cette tablette"
        }
        handOff()
        return if (active) null else lastDecision
    }

    private fun handOff() {
        val intent = CompanionApps.transcriptionLaunchIntent(context)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ) ?: run {
            lastDecision = "Transcription instantanée introuvable au lancement"
            return
        }

        // Le micro AVANT le lancement : le relâcher après laisserait
        // Transcription instantanée démarrer sur un micro déjà pris, ce qu'elle
        // signale par une transcription vide sans expliquer pourquoi.
        onRelease()
        active = true

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bascule vers Transcription instantanée impossible", e)
            lastDecision = "bascule refusée : ${e.message}"
            active = false
            onResume()
            return
        }

        handoffCount++
        lastDecision = "basculé vers Transcription instantanée"
        Log.i(TAG, "Bascule vers Transcription instantanée")

        // Le bandeau après le lancement : posé avant, il serait recouvert par
        // la fenêtre qui s'ouvre.
        handler.postDelayed({ if (active) banner.show { returnToHomeScreen("bandeau") } }, BANNER_DELAY_MS)

        // Drapeau explicite : depuis Android 14, un récepteur enregistré à
        // l'exécution doit dire s'il accepte les diffusions d'autres
        // applications. L'extinction d'écran est une diffusion protégée du
        // système, donc dispensée — mais l'écrire coûte un mot et met à l'abri
        // d'un durcissement ultérieur.
        ContextCompat.registerReceiver(
            context,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // La veille est le chemin normal : elle survient quand la pièce se
        // vide, ce qui est exactement le bon moment. La durée n'est qu'un filet
        // pour le cas où l'écran ne s'éteindrait jamais — Transcription
        // instantanée est faite pour être lue et pourrait le maintenir allumé.
        val minutes = adminConfig.roomHandoffReturnMinutes
        if (minutes > 0) handler.postDelayed(timedReturn, minutes * 60_000L)
    }

    /**
     * Ramène l'écran de Jean. Sans effet si la bascule n'est pas en cours, ce
     * qui rend l'appel sûr depuis n'importe quel chemin de retour — et il y en
     * a quatre, qui peuvent se déclencher coup sur coup.
     */
    fun returnToHomeScreen(reason: String) {
        if (!active) return
        active = false
        lastReturnAtMs = System.currentTimeMillis()
        returnsByReason[reason] = (returnsByReason[reason] ?: 0) + 1
        lastDecision = "revenu ($reason)"
        Log.i(TAG, "Retour à l'écran de Jean : $reason")

        handler.removeCallbacks(timedReturn)
        banner.hide()
        unregisterScreenOff()

        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Retour à l'écran d'accueil refusé", e)
        }
        onResume()
    }

    /**
     * Senior Visio est repassé au premier plan par un autre chemin que les
     * nôtres — le bouton Accueil, ou un appel entrant qui a pris l'écran.
     *
     * Sans ce signal, l'état interne resterait « basculé » : le bandeau
     * flotterait par-dessus l'écran de Jean, voire par-dessus la vidéo d'un
     * appel, et le micro ne serait jamais repris.
     */
    fun noteBackOnHomeScreen() {
        if (!active) return
        returnToHomeScreen("retour manuel")
    }

    private fun unregisterScreenOff() {
        try {
            context.unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            // Jamais enregistré, ou déjà retiré : sans conséquence.
        }
    }

    fun close() {
        handler.removeCallbacks(timedReturn)
        banner.hide()
        if (active) {
            active = false
            try {
                context.unregisterReceiver(screenOffReceiver)
            } catch (e: IllegalArgumentException) {
                // Déjà retiré.
            }
        }
    }

    /** Ce que fait le mode, en une phrase, pour le diagnostic à distance. */
    fun describe(): String = buildString {
        when {
            !adminConfig.roomHandoffEnabled -> {
                append("désactivée")
                return@buildString
            }
            // Vérifié tout de suite, et non à la première voix : une
            // application absente est un blocage permanent, et attendre une
            // voix pour le dire laisse croire que le déclencheur ne part pas.
            !CompanionApps.isTranscriptionInstalled(context) -> {
                append("Transcription instantanée introuvable sur cette tablette")
                return@buildString
            }
            active -> append(
                "en cours — " + if (banner.available()) "bandeau de retour affiché"
                else "retour par le bouton Accueil (superposition non autorisée)"
            )
            else -> append(lastDecision)
        }
        append(", voix entendues ×$voiceHeardCount")
        append(", bascules ×$handoffCount")
        if (returnsByReason.isNotEmpty()) {
            append(", retours : ")
            append(returnsByReason.entries.joinToString(", ") { "${it.key} ×${it.value}" })
        }
        if (refusalsByReason.isNotEmpty()) {
            append(", refus : ")
            append(refusalsByReason.entries.joinToString(", ") { "${it.key} ×${it.value}" })
        }
        if (staleStateRecoveries > 0) append(", états rattrapés ×$staleStateRecoveries")
    }

    private companion object {
        const val TAG = "RoomHandoff"

        /**
         * Pause après un retour avant d'accepter une nouvelle bascule. Deux
         * minutes : de quoi laisser Jean regarder ses photos ou l'heure sans
         * qu'un mot prononcé à côté de lui ne le renvoie aussitôt sur l'autre
         * écran.
         */
        const val RETURN_COOLDOWN_MS = 120_000L

        /** Le temps que la fenêtre de Google s'installe, sans quoi le bandeau passerait dessous. */
        const val BANNER_DELAY_MS = 1_200L
    }
}
