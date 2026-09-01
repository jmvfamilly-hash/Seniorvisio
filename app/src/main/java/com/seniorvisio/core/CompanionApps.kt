package com.seniorvisio.core

/**
 * Applications tierces que Senior Visio a le droit de lancer, et vers
 * lesquelles Jean peut donc basculer sans sortir du mode kiosque.
 *
 * Le principe du produit reste inchangé : Jean ne navigue pas librement dans
 * la tablette, il bascule vers une application choisie ici et en revient par
 * le bouton Accueil (qui ramène à Senior Visio, voir KioskManager).
 *
 * Cette liste est volontairement figée dans le code pour l'instant. Elle a
 * vocation à être alimentée à distance (catalogue Firestore, état désiré
 * réconcilié par la tablette) une fois le canal de distribution sécurisé —
 * ouvrir l'installation d'applications tierces avant d'avoir vérifié
 * empreinte et signature reviendrait à démultiplier une faille existante
 * (voir DeviceStatusReporter.downloadApk).
 */
object CompanionApps {

    /**
     * "Transcription instantanée" de Google : sous-titre en direct les
     * conversations de la pièce. Remplace la reconnaissance vocale maison,
     * abandonnée après six changements de moteur en trois semaines — Google
     * fait mieux sur ce terrain précis (historique consultable, pas de perte
     * de phrase, détection d'événements sonores) et le maintient gratuitement.
     *
     * Préinstallée sur la tablette de Jean (vérifié par
     * `adb shell pm list packages`), donc rien à déployer.
     */
    const val TRANSCRIPTION = "com.google.audio.hearing.visualization.accessibility.scribe"

    /** Paquets autorisés en mode kiosque, en plus de Senior Visio lui-même. */
    val allowedPackages = arrayOf(TRANSCRIPTION)
}
