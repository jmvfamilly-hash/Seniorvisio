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
import com.seniorvisio.recueil.Recueil
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

    /**
     * Le recueil présenté au dernier calcul.
     *
     * ═══ UN RANG NE SUFFIT PAS À DIRE QU'UN CONTENU A CHANGÉ ═══
     *
     * Le changement se décidait sur le seul rang. Or deux recueils — le fil
     * d'information et une galerie photo — tirent leur rang de la MÊME heure
     * de la journée. Installer une galerie à un moment où son rang coïncide
     * avec celui du fil ne produisait donc aucun changement : l'observateur
     * n'était pas prévenu, et l'écran gardait le titre d'actualité précédent
     * jusqu'au créneau suivant.
     *
     * Le réglage était bien arrivé, l'ordonnanceur avait bien basculé, et
     * l'écran ne montrait rien de nouveau. Une panne sans cause visible, dont
     * la probabilité dépend de l'heure à laquelle on installe.
     */
    private var recueilCourant: String? = null
    private var enregistré = false

    /**
     * Ce qui est présenté en ce moment : le fil d'information, ou la galerie
     * photo choisie par l'administrateur.
     *
     * Une classe et non un simple identifiant, parce que les deux n'obéissent
     * pas aux mêmes règles — cadence et réveil de la dalle — et que laisser
     * chaque appelant les redériver de l'identifiant les ferait diverger.
     */
    private data class Présentation(
        val recueil: Recueil?,
        val voulu: String,
        /** Vrai pour une galerie photo : cadence fixe, et JAMAIS de réveil. */
        val estGalerie: Boolean,
    ) {
        val prêts: List<Element> get() = recueil?.prêts.orEmpty()
    }

    /**
     * Ce que l'écran d'accueil doit présenter, d'après les réglages.
     *
     * LA GALERIE PASSE DEVANT LE FIL quand elle est nommée. C'est le même
     * garde-fou que pour les fils d'information : rien n'apparaît de soi-même
     * sur l'écran de Jean, il faut l'avoir demandé.
     */
    private fun présentation(magasin: RecueilStore): Présentation {
        val galerie = config.recueilPhotos.takeIf { it.isNotBlank() }
        val voulu = galerie ?: RECUEIL_FLUX
        return Présentation(
            recueil = magasin.disponibles().firstOrNull { it.id == voulu },
            voulu = voulu,
            estGalerie = galerie != null,
        )
    }

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
        val p = présentation(magasin)
        val prêts = p.prêts

        val créneau = créneauDe(p)
        if (créneau == null || créneau.finDuCreneau == null) {
            // Nuit, ou rien d'installé : l'écran d'accueil reprend ses zones
            // de texte, et rien n'est programmé avant le jour.
            if (rangCourant != -1 || recueilCourant != null) {
                rangCourant = -1
                recueilCourant = null
                observateur?.surTitre(null, null)
            }
            programmerProchainRéveilDeJour(créneau == null)
            return
        }

        // Le RECUEIL compte autant que le rang : voir recueilCourant.
        val changement = créneau.rang != rangCourant || p.voulu != recueilCourant
        rangCourant = créneau.rang
        recueilCourant = p.voulu
        if (changement) {
            observateur?.surTitre(prêts.getOrNull(créneau.rang), p.recueil?.id)
            CallTraceActualite.noter(créneau.rang, prêts.size, créneau.finDuCreneau, p.voulu)
        }
        // ═══ UNE PHOTO NE RALLUME JAMAIS LA DALLE ═══
        //
        // Un titre d'actualité qui change vaut un réveil : c'est une nouvelle,
        // et la dalle s'allume pour la donner. Une photo de famille, non — la
        // galerie est là pour que l'écran soit agréable QUAND on le regarde,
        // pas pour réclamer qu'on le regarde. Vingt-quatre réveils par jour
        // pour faire défiler des photos, c'est une tablette qui s'allume
        // toute seule toutes les heures dans une chambre.
        //
        // Elles restent donc « visibles seulement quand la tablette n'est pas
        // en veille », exactement comme demandé — et la bonne photo est à
        // l'écran dès qu'il se rallume, puisque le rang se déduit de l'heure
        // (voir CadenceurActualites) et que le rebranchement la livre.
        if (réveillerLÉcran && changement && !p.estGalerie) rallumerLÉcran()

        programmer(créneau.finDuCreneau)
    }

    /** La cadence qui convient : fixe pour une galerie, divisée pour le fil. */
    private fun créneauDe(p: Présentation): CadenceurActualites.Creneau? =
        if (p.estGalerie) {
            CadenceurActualites.creneauFixe(
                LocalDateTime.now(), p.prêts.size, MINUTES_PAR_PHOTO, config,
            )
        } else {
            CadenceurActualites.creneau(LocalDateTime.now(), p.prêts.size, config)
        }

    var observateur: Observateur? = null

    /**
     * Branche un écran ET lui livre tout de suite ce qu'il doit montrer.
     *
     * ═══ POURQUOI POSER L'OBSERVATEUR NE SUFFISAIT PAS ═══
     *
     * L'écran d'accueil se débranche à onPause pour ne pas être retenu en
     * mémoire par le service, et se rebranche à onResume. Pendant ce temps
     * l'ordonnanceur continue de tourner sur le battement du service et MET À
     * JOUR SON RANG DANS LE VIDE : les changements partent vers un observateur
     * nul.
     *
     * Au retour, réévaluer comparait donc le rang courant à lui-même,
     * concluait « aucun changement », et ne poussait rien. Or la liste des
     * titres de l'écran n'est alimentée que par cet observateur : l'écran
     * restait sur ce qu'il avait, ou sur rien.
     *
     * C'est aussi ce qui rend tenable la règle « une photo ne rallume pas la
     * dalle » : l'écran qui se rallume tout seul, ou que Jean réveille,
     * reçoit ici la photo du moment sans avoir à attendre le créneau suivant.
     */
    fun brancher(nouvel: Observateur) {
        observateur = nouvel
        val magasin = RecueilStore.actif ?: return
        val p = présentation(magasin)
        val créneau = créneauDe(p)
        if (créneau?.finDuCreneau == null) {
            // Nuit ou rien d'installé : l'écran doit le savoir aussi, sinon il
            // garderait la dernière photo affichée avant sa mise en pause.
            rangCourant = -1
            recueilCourant = null
            nouvel.surTitre(null, null)
            return
        }
        // Ce qui vient d'être livré est MÉMORISÉ : sans ces deux lignes, le
        // réévaluer qui suit systématiquement brancher trouverait un rang à
        // -1, conclurait « changement », et pousserait une seconde fois le
        // même élément — deux décodages de bitmap et un clignotement à chaque
        // retour d'écran.
        rangCourant = créneau.rang
        recueilCourant = p.voulu
        nouvel.surTitre(p.prêts.getOrNull(créneau.rang), p.recueil?.id)
    }

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

        /**
         * Un quart d'heure par photo, comme demandé.
         *
         * Assez long pour qu'une photo ne soit pas un défilement — une image
         * qui change sous les yeux réclame l'attention au lieu de l'apaiser —
         * et assez court pour qu'une galerie de trente photos fasse deux tours
         * dans une journée éveillée.
         */
        const val MINUTES_PAR_PHOTO = 15
        const val RENDEZ_VOUS_À_VIDE_MS = 15 * 60 * 1000L
    }
}

/** Trace lisible dans le journal technique, séparée pour ne pas alourdir la logique. */
internal object CallTraceActualite {
    fun noter(rang: Int, total: Int, fin: LocalDateTime, recueil: String) {
        com.seniorvisio.core.CallTrace.record(
            "ACCUEIL actualité",
            // Le recueil est NOMMÉ : un compte d'éléments n'est pas une
            // identité, et « 24/25 » a déjà dû être rapproché à la main d'un
            // « FLUX publié | 17 titre(s) » pour savoir ce qui était à l'écran.
            "titre ${rang + 1}/$total · recueil=$recueil · jusqu'à ${fin.toLocalTime()}",
        )
    }
}
