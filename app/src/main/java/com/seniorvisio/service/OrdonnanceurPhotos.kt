package com.seniorvisio.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.recueil.CadenceurPhotos
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
class OrdonnanceurPhotos(private val service: CallListenerService) {

    /** Ce que l'écran d'accueil doit montrer, ou null s'il n'y a rien. */
    fun interface Observateur {
        fun surPhoto(element: Element?, recueilId: String?)
    }

    private val config by lazy { AdminConfig(service) }
    private var rangCourant = -1

    /**
     * Le recueil présenté au dernier calcul.
     *
     * ═══ UN RANG NE SUFFIT PAS À DIRE QU'UN CONTENU A CHANGÉ ═══
     *
     * Le changement se décidait sur le seul rang. Or deux recueils tirent
     * leur rang de la MÊME heure de la journée : basculer d'une galerie à une
     * autre à un moment où leurs rangs coïncident ne produisait donc aucun
     * changement. L'observateur n'était pas prévenu, et l'écran gardait la
     * photo précédente — celle de l'ancienne galerie — jusqu'au créneau
     * suivant.
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
    ) {
        val prêts: List<Element> get() = recueil?.prêts.orEmpty()
    }

    /**
     * La galerie que l'écran d'accueil doit présenter, ou null.
     *
     * Null tant que l'administrateur n'en a pas choisi une : rien n'apparaît
     * de soi-même sur l'écran de Jean, il faut l'avoir demandé. L'accueil
     * garde alors ses zones de texte.
     */
    private fun présentation(magasin: RecueilStore): Présentation? {
        val voulu = config.recueilPhotos.takeIf { it.isNotBlank() } ?: return null
        return Présentation(
            recueil = magasin.disponibles().firstOrNull { it.id == voulu },
            voulu = voulu,
        )
    }

    private val réveil = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Réveil programmé : changement de photo")
            réévaluer()
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
        réévaluer()
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
    fun réévaluer() {
        val magasin = RecueilStore.actif ?: return
        val p = présentation(magasin)
        val prêts = p?.prêts.orEmpty()

        val créneau = p?.let { créneauDe(it) }
        if (créneau == null || créneau.finDuCreneau == null) {
            // Nuit, ou rien d'installé : l'écran d'accueil reprend ses zones
            // de texte, et rien n'est programmé avant le jour.
            if (rangCourant != -1 || recueilCourant != null) {
                rangCourant = -1
                recueilCourant = null
                observateur?.surPhoto(null, null)
            }
            programmerProchainRéveilDeJour(créneau == null)
            return
        }

        // Le RECUEIL compte autant que le rang : voir recueilCourant.
        val changement = créneau.rang != rangCourant || p!!.voulu != recueilCourant
        rangCourant = créneau.rang
        recueilCourant = p.voulu
        if (changement) {
            observateur?.surPhoto(prêts.getOrNull(créneau.rang), p.recueil?.id)
            TracePhoto.noter(créneau.rang, prêts.size, créneau.finDuCreneau, p.voulu)
        }
        programmer(créneau.finDuCreneau)
    }

    /**
     * La cadence réglée par l'administrateur, bornée à la lecture.
     *
     * Bornée ICI et pas seulement dans le curseur du PWA : ce document est
     * ouvert en écriture à qui en connaît l'adresse, et une valeur de zéro
     * ferait tourner les photos à chaque battement — ou diviser par zéro.
     */
    private fun créneauDe(p: Présentation): CadenceurPhotos.Creneau? =
        CadenceurPhotos.creneau(
            LocalDateTime.now(),
            p.prêts.size,
            config.cadencePhotosMinutes.coerceIn(MINUTES_MIN, MINUTES_MAX),
            config,
        )

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
        val créneau = p?.let { créneauDe(it) }
        if (créneau?.finDuCreneau == null) {
            // Nuit ou rien d'installé : l'écran doit le savoir aussi, sinon il
            // garderait la dernière photo affichée avant sa mise en pause.
            rangCourant = -1
            recueilCourant = null
            nouvel.surPhoto(null, null)
            return
        }
        // Ce qui vient d'être livré est MÉMORISÉ : sans ces deux lignes, le
        // réévaluer qui suit systématiquement brancher trouverait un rang à
        // -1, conclurait « changement », et pousserait une seconde fois le
        // même élément — deux décodages de bitmap et un clignotement à chaque
        // retour d'écran.
        rangCourant = créneau.rang
        recueilCourant = p!!.voulu
        nouvel.surPhoto(p.prêts.getOrNull(créneau.rang), p.recueil?.id)
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
        Log.i(TAG, "Prochaine photo à $quand")
    }

    /** Rien à montrer pour l'instant : on se redonne rendez-vous dans un quart d'heure. */
    private fun programmerProchainRéveilDeJour(aucunePhoto: Boolean) {
        val dans = System.currentTimeMillis() + RENDEZ_VOUS_À_VIDE_MS
        alarmes()?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dans, intentionDeRéveil())
        if (aucunePhoto) {
            // Dans le journal et pas seulement dans logcat : la tablette peut
            // se redonner rendez-vous toutes les quinze minutes pendant des
            // heures, et « aucune ligne » se lirait alors exactement comme
            // « la tablette est éteinte ».
            //
            // Une seule fois par cause, sinon ces lignes évinceraient tout le
            // reste du tampon d'état en une nuit.
            if (!videSignalé) {
                videSignalé = true
                com.seniorvisio.core.CallTrace.record(
                    "GALERIE vide",
                    "aucune photo affichable — nouvel essai toutes les 15 min",
                )
            }
            Log.i(TAG, "Aucune photo installée — nouvel essai dans 15 min")
        } else {
            videSignalé = false
        }
    }

    /** Voir programmerProchainRéveilDeJour : une ligne par cause, pas par essai. */
    private var videSignalé = false

    private companion object {
        const val TAG = "OrdonnanceurPhotos"
        const val ACTION_CHANGEMENT = "com.seniorvisio.PHOTO_SUIVANTE"

        /**
         * Les bornes de la cadence réglable (voir AdminConfig.cadencePhotosMinutes).
         *
         * Une minute au plancher : en dessous, une photo qui change sous les
         * yeux réclame l'attention au lieu de l'apaiser. Une heure au
         * plafond : au-delà, la galerie ne tourne plus, et l'administrateur
         * ferait mieux de n'y mettre qu'une photo.
         */
        const val MINUTES_MIN = 1
        const val MINUTES_MAX = 60
        const val RENDEZ_VOUS_À_VIDE_MS = 15 * 60 * 1000L
    }
}

/** Trace lisible dans le journal technique, séparée pour ne pas alourdir la logique. */
internal object TracePhoto {
    fun noter(rang: Int, total: Int, fin: LocalDateTime, recueil: String) {
        com.seniorvisio.core.CallTrace.record(
            "ACCUEIL photo",
            // Le recueil est NOMMÉ : un compte d'éléments n'est pas une
            // identité, et « 24/25 » a déjà dû être rapproché à la main d'un
            // « FLUX publié | 17 titre(s) » pour savoir ce qui était à l'écran.
            "photo ${rang + 1}/$total · recueil=$recueil · jusqu'à ${fin.toLocalTime()}",
        )
    }
}
