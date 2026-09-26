package com.seniorvisio.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.WindowManager
import com.seniorvisio.admin.SeniorVisioDeviceAdminReceiver

/**
 * Éteindre la dalle, à la demande de Jean.
 *
 * ═══ DEUX CHEMINS, PARCE QU'UN SEUL NE MARCHE PAS PARTOUT ═══
 *
 * Android n'autorise pas une application ordinaire à endormir l'écran. Deux
 * moyens existent, et le second n'est pas un repli décoratif :
 *
 *   1. `lockNow()`, réservé aux administrateurs d'appareil. La tablette de Jean
 *      en est un — c'est ce qui permet déjà le mode kiosque (voir
 *      KioskManager). L'extinction est alors IMMÉDIATE, ce que Jean attend
 *      quand il appuie.
 *
 *   2. Sinon, on relâche le maintien de l'écran allumé et on laisse la
 *      temporisation du système faire son travail. L'écran s'éteint, mais au
 *      bout de son délai habituel — une trentaine de secondes, parfois plus.
 *
 * La tablette d'essai n'est pas toujours provisionnée en administrateur : sans
 * le second chemin, le bouton n'y ferait RIEN, et on conclurait que la fonction
 * est cassée alors qu'elle marche chez Jean. Le chemin emprunté est donc écrit
 * dans le journal à chaque fois.
 *
 * ═══ ET MAINTENANT, ÇA DURE ═══
 *
 * Ce bouton éteignait la dalle, et rien de plus. Le prochain créneau de
 * photos la rallumait un quart d'heure plus tard, ou le premier bruit dans la
 * pièce quelques secondes après. Du point de vue de Jean, le bouton ne
 * marchait pas — alors qu'il faisait précisément ce que le code disait.
 *
 * Un appui pose désormais une échéance, douze heures par défaut (voir
 * AdminConfig.sleepHours), et les deux chemins de réveil la respectent : le
 * changement de photo (OrdonnanceurPhotos.rallumerLÉcran) et le son de la
 * pièce (RoomPresenceService). Jean appuie le soir, l'écran reste noir
 * jusqu'au matin.
 *
 * ═══ CE QUE LE SOMMEIL NE BLOQUE PAS ═══
 *
 * Un appel. Rien n'est arrêté : ni l'écoute des appels, ni le service. La
 * tablette sonne, et l'échéance est levée au passage — sans quoi l'écran
 * resterait noir pendant que quelqu'un essaie de joindre Jean.
 *
 * C'est la condition pour lui donner ce bouton : il doit pouvoir faire taire
 * son écran sans jamais pouvoir se couper du monde.
 */
object MiseEnVeille {

    /**
     * @param fenêtre la fenêtre de l'écran qui demande la veille, pour lui
     *   retirer son maintien allumé. Sans ce retrait, le drapeau reposé à
     *   chaque battement (voir MainActivity.screenAwakeTicker) rallumerait
     *   l'écran dans la seconde — le bouton aurait alors l'air de ne rien
     *   faire, ce qui est pire que de ne pas exister.
     */
    fun endormir(context: Context, fenêtre: android.view.Window?) {
        fenêtre?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val config = AdminConfig(context)
        // Bornée à la lecture comme à l'écriture : ce réglage transite par un
        // document Firestore ouvert en écriture à qui en connaît l'adresse
        // (voir DeviceStatusReporter). Une valeur aberrante — négative, ou
        // mille heures — ferait un écran noir que personne sur place ne
        // saurait rallumer.
        val heures = config.sleepHours.coerceIn(MIN_HEURES, MAX_HEURES)
        config.sleepUntilMs = System.currentTimeMillis() + heures * 3_600_000L
        CallTrace.record("SOMMEIL demandé", "écran noir pour $heures h, sauf appel")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, SeniorVisioDeviceAdminReceiver::class.java)
        if (dpm != null && dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
                .onSuccess { CallTrace.record("VEILLE demandée", "extinction immédiate (administrateur d'appareil)") }
                .onFailure { e ->
                    // Refus du système malgré le statut d'administrateur : dit,
                    // et non avalé. Sans cette ligne, le bouton resterait sans
                    // effet visible et sans explication nulle part.
                    Log.w(TAG, "lockNow refusé", e)
                    CallTrace.record("VEILLE refusée", "${e.javaClass.simpleName} — repli sur la temporisation")
                }
        } else {
            CallTrace.record(
                "VEILLE demandée",
                "pas administrateur d'appareil — l'écran s'éteindra à la temporisation du système",
            )
        }
    }

    /**
     * Lève le sommeil en cours, s'il y en a un.
     *
     * Appelée à l'arrivée d'un appel, et par le bouton « Réveiller » du
     * panneau d'administration. Rend vrai quand il y avait effectivement un
     * sommeil à lever, pour que l'appelant puisse le dire au journal sans
     * avoir à relire le réglage lui-même.
     */
    fun réveiller(context: Context, motif: String): Boolean {
        val config = AdminConfig(context)
        if (!config.isSleeping()) return false
        val restantMin = (config.sleepUntilMs - System.currentTimeMillis()) / 60_000L
        config.sleepUntilMs = 0L
        CallTrace.record("SOMMEIL levé", "$motif — il restait $restantMin min")
        return true
    }

    /** Ce que le panneau d'administration et le signe de vie affichent. */
    fun décrire(context: Context): String {
        val config = AdminConfig(context)
        if (!config.isSleeping()) return "éveillée (appui sur « Sommeil » : ${config.sleepHours} h)"
        val restantMin = (config.sleepUntilMs - System.currentTimeMillis()) / 60_000L
        return "en sommeil encore $restantMin min"
    }

    private const val TAG = "MiseEnVeille"

    /**
     * Une heure au minimum : en dessous, le bouton retombe dans le défaut
     * qu'on corrige ici — un écran qui se rallume avant que Jean ait eu le
     * temps de s'endormir. Vingt-quatre au plus : au-delà, plus personne ne
     * se souvient d'avoir appuyé.
     */
    private const val MIN_HEURES = 1
    private const val MAX_HEURES = 24
}
