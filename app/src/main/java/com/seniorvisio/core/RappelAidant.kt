package com.seniorvisio.core

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.seniorvisio.BuildConfig

/**
 * Demande de rappel par notification push : Jean presse un bouton, un
 * document part dans BuildConfig.RAPPELS_COLLECTION, et une Cloud Function
 * (voir functions/index.js) lit le jeton de notification de l'aidant sur le
 * document d'appareil et lui envoie une alerte — voir docs du bouton dans
 * activity_main.xml.
 *
 * Ne sait rien du jeton de l'aidant, volontairement : il ne vit que côté
 * Cloud Function et navigateur de l'aidant (voir AdminConfig.aidantNom, qui
 * ne porte que le prénom affiché sur le bouton, jamais le jeton).
 */
object RappelAidant {
    fun demander() {
        FirebaseFirestore.getInstance()
            .collection(BuildConfig.RAPPELS_COLLECTION)
            .add(mapOf(CHAMP_DEMANDÉ_LE to FieldValue.serverTimestamp()))
            .addOnFailureListener { e ->
                CallTrace.record("RAPPEL AIDANT ÉCHEC", "demande non transmise : ${e.message}")
            }
        CallTrace.record("RAPPEL AIDANT", "demande envoyée")
    }

    private const val CHAMP_DEMANDÉ_LE = "demandéLe"
}
