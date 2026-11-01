package com.seniorvisio.core

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.seniorvisio.BuildConfig

/**
 * Demande de rappel par SMS : Jean presse un bouton, un document part dans
 * BuildConfig.RAPPELS_COLLECTION, et une Cloud Function (voir
 * functions/index.js) lit le téléphone de l'aidant sur le document
 * d'appareil et envoie le SMS — voir docs du bouton dans activity_main.xml.
 *
 * Ne sait rien de Twilio ni du numéro de l'aidant, volontairement : le
 * numéro ne vit que côté Cloud Function (voir AdminConfig.aidantNom, qui ne
 * porte que le prénom affiché sur le bouton, jamais le téléphone).
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
