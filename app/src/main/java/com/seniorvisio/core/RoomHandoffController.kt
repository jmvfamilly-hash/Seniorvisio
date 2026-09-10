package com.seniorvisio.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /** Dernière raison d'agir ou de ne pas agir, publiée au diagnostic. */
    @Volatile var lastDecision: String = "inactif"
        private set

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
        if (active) return
        val reason = refuseReason()
        if (reason != null) {
            lastDecision = reason
            return
        }
        handOff()
    }

    /**
     * Ce qui interdit la bascule à cet instant, ou null si elle est permise.
     *
     * Énumérées dans l'ordre où elles s'appliquent, et chacune nommée : sans
     * ça, un mode qui ne bascule pas ressemble à un mode en panne, et les cinq
     * causes se corrigent différemment.
     */
    private fun refuseReason(): String? {
        if (!adminConfig.roomHandoffEnabled) return "inactif"
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

        lastDecision = "basculé vers Transcription instantanée"
        Log.i(TAG, "Bascule vers Transcription instantanée")

        // Le bandeau après le lancement : posé avant, il serait recouvert par
        // la fenêtre qui s'ouvre.
        handler.postDelayed({ if (active) banner.show { returnToHomeScreen("bandeau") } }, BANNER_DELAY_MS)

        context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

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
        lastDecision = "revenu ($reason)"
        Log.i(TAG, "Retour à l'écran de Jean : $reason")

        handler.removeCallbacks(timedReturn)
        banner.hide()
        try {
            context.unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            // Jamais enregistré, ou déjà retiré : sans conséquence.
        }

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
    fun describe(): String = when {
        !adminConfig.roomHandoffEnabled -> "désactivée"
        active -> "en cours — ${if (banner.available()) "bandeau de retour affiché"
        else "retour par le bouton Accueil (autorisation de superposition non accordée)"}"
        else -> lastDecision
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
