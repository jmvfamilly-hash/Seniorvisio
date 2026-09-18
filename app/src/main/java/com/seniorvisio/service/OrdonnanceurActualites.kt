package com.seniorvisio.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.recueil.CadenceurActualites
import com.seniorvisio.recueil.Element
import com.seniorvisio.recueil.RecueilStore
import com.seniorvisio.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fait vivre le fil d'information sur l'écran d'accueil, au rythme de la
 * journée.
 *
 * ═══ CE QU'IL FAIT, ET DANS QUEL ORDRE ═══
 *
 *   1. demande au cadenceur quel titre est celui de maintenant ;
 *   2. le publie, pour que l'écran d'accueil l'affiche — qu'il soit allumé ou
 *      non, car c'est ce qui garantit qu'une dalle qui s'allume pour une tout
 *      autre raison montre déjà le bon titre ;
 *   3. programme un réveil pour la fin du créneau ;
 *   4. à ce réveil, rallume la dalle pour la durée réglée, et recommence.
 *
 * ═══ POURQUOI UN ALARMMANAGER ET PAS UNE MINUTERIE ═══
 *
 * Android endort le processeur. Une minuterie ordinaire ne se déclenche alors
 * qu'au prochain réveil de la machine, c'est-à-dire n'importe quand — le titre
 * de neuf heures apparaîtrait à onze. Seul un réveil programmé auprès du
 * système traverse la veille.
 *
 * `setAndAllowWhileIdle` et non `setExactAndAllowWhileIdle` : la version
 * exacte réclame depuis Android 12 une permission que l'utilisateur doit
 * accorder à la main, ce qu'on ne peut pas demander sur une tablette d'essai
 * qu'on flashe. Le prix est une imprécision de quelques minutes sur un créneau
 * qui en dure trente — sans conséquence — et le battement de cœur du service
 * rattrape de toute façon toute dérive.
 */
class OrdonnanceurActualites(private val service: CallListenerService) {

    /** Ce que l'écran d'accueil doit montrer, ou null s'il n'y a rien. */
    fun interface Observateur {
        fun surTitre(element: Element?, recueilId: String?)
    }

    private val config by lazy { AdminConfig(service) }
    private var rangCourant = -1
    private var enregistré = false

    private val réveil = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Réveil programmé : changement de titre")
            réévaluer(réveillerLÉcran = true)
        }
    }

    fun démarrer() {
        if (enregistré) return
        enregistré = true
        val filtre = IntentFilter(ACTION_CHANGEMENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(réveil, filtre, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            service.registerReceiver(réveil, filtre)
        }
        réévaluer(réveillerLÉcran = false)
    }

    fun arrêter() {
        if (!enregistré) return
        enregistré = false
        runCatching { service.unregisterReceiver(réveil) }
        alarmes()?.cancel(intentionDeRéveil())
    }

    /**
     * Recalcule le titre courant et reprogramme la suite.
     *
     * Appelé aussi par le battement de cœur du service : si un réveil s'est
     * perdu — système qui a repoussé l'alarme, tablette éteinte au moment dit —
     * le titre se remet d'aplomb au prochain passage, sans rien de spécial à
     * prévoir pour ce cas.
     */
    fun réévaluer(réveillerLÉcran: Boolean) {
        val magasin = RecueilStore.actif ?: return
        // ═══ LES ŒUVRES PASSENT DEVANT LE FIL, QUAND ELLES SONT NOMMÉES ═══
        //
        // Un seul ordonnanceur pour les deux : la cadence, le réveil de la
        // dalle, le rattrapage des alarmes perdues et la navigation de Jean
        // sont exactement les mêmes besoins. En écrire un second aurait
        // condamné les deux à diverger — c'est déjà arrivé ici entre l'accueil
        // et l'écran d'appel, deux fois.
        //
        // Ce qui change, c'est le CONTENU d'un élément : un titre d'actualité
        // est un texte, une œuvre est une image. L'écran d'accueil sait
        // désormais afficher les deux (voir MainActivity.afficherActualiteCourante).
        val voulu = config.recueilOeuvres.takeIf { it.isNotBlank() } ?: RECUEIL_FLUX
        val recueil = magasin.disponibles().firstOrNull { it.id == voulu }
        val prêts = recueil?.prêts.orEmpty()

        val créneau = CadenceurActualites.creneau(LocalDateTime.now(), prêts.size, config)
        if (créneau == null || créneau.finDuCreneau == null) {
            // Nuit, ou aucun titre installé : l'écran d'accueil reprend ses
            // zones de texte, et rien n'est programmé avant le jour.
            if (rangCourant != -1) {
                rangCourant = -1
                observateur?.surTitre(null, null)
            }
            programmerProchainRéveilDeJour(créneau == null)
            return
        }

        val changement = créneau.rang != rangCourant
        rangCourant = créneau.rang
        if (changement) {
            observateur?.surTitre(prêts.getOrNull(créneau.rang), recueil?.id)
            CallTraceActualite.noter(créneau.rang, prêts.size, créneau.finDuCreneau)
        }
        // Le réveil n'est demandé que sur un VRAI changement : une réévaluation
        // de rattrapage ne doit pas rallumer l'écran pour le titre déjà affiché.
        if (réveillerLÉcran && changement) rallumerLÉcran()

        programmer(créneau.finDuCreneau)
    }

    var observateur: Observateur? = null

    // ── Réveils ────────────────────────────────────────────────

    private fun alarmes() = service.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun intentionDeRéveil(): PendingIntent = PendingIntent.getBroadcast(
        service,
        0,
        Intent(ACTION_CHANGEMENT).setPackage(service.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun programmer(quand: LocalDateTime) {
        val instant = quand.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmes()?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, intentionDeRéveil())
        Log.i(TAG, "Prochain titre à $quand")
    }

    /** Rien à montrer pour l'instant : on se redonne rendez-vous dans un quart d'heure. */
    private fun programmerProchainRéveilDeJour(aucunTitre: Boolean) {
        val dans = System.currentTimeMillis() + RENDEZ_VOUS_À_VIDE_MS
        alarmes()?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dans, intentionDeRéveil())
        if (aucunTitre) {
            Log.i(TAG, "Aucun titre installé — nouvel essai dans 15 min")
            // Et dans le journal, cette fois. Ce chemin n'écrivait que dans
            // logcat : la tablette pouvait se redonner rendez-vous toutes les
            // quinze minutes pendant des heures sans qu'une seule ligne ne le
            // dise à qui relève le journal à distance.
            com.seniorvisio.core.VieDuFil.noterAucunTitre()
        }
    }

    /**
     * Allume la dalle pour la durée réglée par l'administrateur.
     *
     * Le verrou porte sa propre échéance : si quoi que ce soit empêchait sa
     * libération, l'écran ne resterait pas allumé indéfiniment. Ce projet a
     * déjà écrit cette précaution ailleurs, pour la même raison (voir
     * RoomPresenceService.ensureAwake).
     */
    private fun rallumerLÉcran() {
        if (config.blockWakeAtNight && config.isCurrentlyNightWindow(LocalDateTime.now().hour)) return
        val gestionnaire = service.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val durée = config.dureeEveilActualiteSecondes.coerceIn(30, 3600) * 1000L

        @Suppress("DEPRECATION")
        val verrou = gestionnaire.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "SeniorVisio:Actualite",
        )
        verrou.acquire(durée)

        // Écran déjà allumé : surtout ne pas ramener l'accueil par-dessus ce
        // que Jean regarde — un appel en cours, par exemple.
        if (gestionnaire.isInteractive) return
        try {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Réveil de l'écran refusé par le système", e)
        }
    }

    private companion object {
        const val TAG = "OrdonnanceurActu"
        const val ACTION_CHANGEMENT = "com.seniorvisio.ACTUALITE_SUIVANTE"
        const val RECUEIL_FLUX = "flux-actualites"
        const val RENDEZ_VOUS_À_VIDE_MS = 15 * 60 * 1000L
    }
}

/** Trace lisible dans le journal technique, séparée pour ne pas alourdir la logique. */
internal object CallTraceActualite {
    fun noter(rang: Int, total: Int, fin: LocalDateTime) {
        com.seniorvisio.core.CallTrace.record(
            "ACCUEIL actualité",
            "titre ${rang + 1}/$total · jusqu'à ${fin.toLocalTime()}",
        )
    }
}
