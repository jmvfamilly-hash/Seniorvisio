package com.seniorvisio.core

/**
 * D'où vient le texte affiché à Jean. C'est cette information, et elle
 * seule, qui détermine dans quelle zone de l'écran il apparaît (voir
 * HomeZonesController) : la couche d'affichage n'a pas à savoir s'il y a un
 * appel en cours, si la tablette a décroché, ni qui a appuyé sur quoi.
 *
 * La question qui tranche entre ROOM et le reste est simple : est-ce le
 * microphone de la tablette qui entend, ou non ? PUBLIC n'est pas transcrit
 * du tout — c'est un texte déjà écrit (voir
 * docs/architecture-message-public.md) — mais il partage la même zone que
 * CALL, pour la même raison : ni l'un ni l'autre ne vient du micro.
 */
enum class TranscriptionSource {
    /** Le microphone de la tablette : ce qui se dit dans la pièce, auprès de Jean. */
    ROOM,

    /** Tout le reste : aujourd'hui le son reçu d'un appel, donc la voix de la personne au bout du fil. */
    CALL,

    /** Un mot écrit par un proche depuis le PWA, affiché sur l'accueil tant qu'il n'a pas expiré. */
    PUBLIC,
}
