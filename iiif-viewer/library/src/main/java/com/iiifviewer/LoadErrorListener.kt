package com.iiifviewer

/**
 * Écoute des échecs de chargement de tuiles, y compris ceux qu'un nouvel essai rattrape : l'appelant (un journal de diagnostic)
 * voit ainsi ce qui est fragile même quand tout finit par s'afficher.
 *
 * @param attempt numéro de l'essai qui vient d'échouer (1, 2…)
 * @param finalAttempt vrai si c'était le dernier : la tuile est abandonnée (le visualiseur la redemandera plus tard)
 */
fun interface LoadErrorListener {
    fun onError(url: String, attempt: Int, error: Throwable, finalAttempt: Boolean)
}
