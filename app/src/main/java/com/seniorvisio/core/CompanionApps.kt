package com.seniorvisio.core

/**
 * Applications tierces que Senior Visio a le droit de lancer, et vers
 * lesquelles Jean peut donc basculer sans sortir du mode kiosque.
 *
 * Le principe du produit reste inchangé : Jean ne navigue pas librement dans
 * la tablette, il bascule vers une application choisie ici et en revient par
 * le bouton Accueil (qui ramène à Senior Visio, voir KioskManager).
 *
 * Vide pour l'instant : la transcription de la pièce, seule fonction qui en
 * dépendait ("Transcription instantanée" de Google), est désormais un écran
 * de Senior Visio lui-même (voir RoomTranscriptionActivity, AssemblyAI) —
 * plus une application tierce à autoriser. Conservé prêt à l'emploi pour une
 * future application compagne, plutôt que supprimé.
 */
object CompanionApps {
    /** Paquets autorisés en mode kiosque, en plus de Senior Visio lui-même. */
    val allowedPackages = arrayOf<String>()
}
