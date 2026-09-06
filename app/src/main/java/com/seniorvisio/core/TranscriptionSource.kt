package com.seniorvisio.core

/**
 * D'où vient le son qu'on transcrit. C'est cette information, et elle seule,
 * qui détermine dans quelle zone de l'écran de Jean le texte s'affiche (voir
 * HomeZonesController) : la couche d'affichage n'a pas à savoir s'il y a un
 * appel en cours, si la tablette a décroché, ni qui a appuyé sur quoi.
 *
 * Les deux cas se distinguent par une question simple, et une seule : est-ce
 * le microphone de la tablette qui entend, ou non ?
 */
enum class TranscriptionSource {
    /** Le microphone de la tablette : ce qui se dit dans la pièce, auprès de Jean. */
    ROOM,

    /** Tout le reste : aujourd'hui le son reçu d'un appel, donc la voix de la personne au bout du fil. */
    CALL,
}
