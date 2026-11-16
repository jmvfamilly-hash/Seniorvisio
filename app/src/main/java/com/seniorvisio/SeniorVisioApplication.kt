package com.seniorvisio

import android.app.Application
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

/**
 * Purge le cache Firestore local à chaque changement réel de version.
 *
 * ═══ CE QUE ÇA CORRIGE ═══
 *
 * Firestore sur Android garde par défaut une file d'écritures non confirmées
 * sur le disque de la tablette, pas seulement en mémoire — elle survit donc
 * à un redémarrage de l'application ET de l'appareil, et ne se vide que
 * d'elle-même, à son rythme, une fois le réseau revenu. Après la coupure
 * Wifi prolongée de début novembre, cette file a continué à recevoir un
 * signe de vie toutes les cinq minutes sans jamais pouvoir le confirmer.
 * À la reconnexion, ces anciennes valeurs ont mis des heures à se purger —
 * assez pour qu'une version révolue continue d'apparaître dans Firestore
 * bien après que la tablette ait réellement basculé sur du code plus
 * récent. Le même mécanisme, partagé par tout ce qui utilise Firestore
 * dans l'application (signe de vie, mais aussi la signalisation des
 * appels), peut tout aussi bien retarder une offre, une réponse ou un
 * candidat ICE.
 *
 * ═══ POURQUOI AU DÉMARRAGE DE L'APPLI, PAS PAR COMMANDE À DISTANCE ═══
 *
 * Une commande à distance passerait par ce même canal Firestore qu'on
 * cherche justement à débloquer — elle risquerait de rester coincée
 * derrière la même file qu'elle est censée vider. Comparer le numéro de
 * version courant à celui gardé localement (hors Firestore, dans les
 * préférences de l'application) ne dépend d'aucun réseau : la purge se
 * déclenche d'elle-même dès qu'une mise à jour réelle vient d'avoir lieu,
 * avant qu'aucun composant n'ait eu la moindre chance de toucher Firestore.
 *
 * ═══ POURQUOI ATTENDRE LA PURGE AVANT DE CONTINUER ═══
 *
 * [clearPersistence] exige qu'aucune autre opération Firestore n'ait encore
 * démarré — un service qui s'y connecterait une fraction de seconde avant
 * ferait échouer la purge sans bruit. L'attendre ici, dès le tout premier
 * instant de l'application et seulement le jour d'une mise à jour, coûte au
 * pire quelques centaines de millisecondes une fois par version.
 */
class SeniorVisioApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        clearFirestoreCacheIfJustUpdated()
    }

    private fun clearFirestoreCacheIfJustUpdated() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val versionActuelle = BuildConfig.BUILD_REV
        val dernièreVersionConnue = prefs.getString(KEY_LAST_BUILD_REV, null)
        if (dernièreVersionConnue == versionActuelle) return

        Log.i(TAG, "Changement de version détecté ($dernièreVersionConnue → $versionActuelle) : purge du cache Firestore local")
        runCatching {
            Tasks.await(FirebaseFirestore.getInstance().clearPersistence(), 5, TimeUnit.SECONDS)
        }.onFailure { e ->
            // Pas bloquant : au pire, l'ancienne file continue de se vider
            // comme avant ce correctif, ce qui est l'état qu'on connaissait déjà.
            Log.w(TAG, "Purge du cache Firestore refusée ou expirée", e)
        }
        prefs.edit().putString(KEY_LAST_BUILD_REV, versionActuelle).apply()
    }

    private companion object {
        const val TAG = "SeniorVisioApplication"
        const val PREFS_NAME = "seniorvisio_app_state"
        const val KEY_LAST_BUILD_REV = "last_build_rev"
    }
}
