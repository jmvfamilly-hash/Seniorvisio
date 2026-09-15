package com.seniorvisio.core

import com.seniorvisio.BuildConfig

/**
 * Ce que cette version de l'application est, et où elle écrit.
 *
 * ═══ POURQUOI CE REPÈRE EXISTE ═══
 *
 * Deux tablettes font tourner le même code sur des documents Firestore
 * différents (voir les variantes dans app/build.gradle). Rien à l'écran ne
 * les distinguait : même interface, même numéro de version, même tout. Or
 * confondre les deux mène exactement aux deux erreurs qu'on veut éviter —
 * croire qu'on est sur la tablette d'essai alors qu'on règle celle de Jean,
 * ou chercher pendant une heure pourquoi un appel n'arrive pas sur une
 * tablette qui n'écoute pas la même boîte aux lettres.
 *
 * La marque n'est donc pas décorative, et elle n'est affichée QUE hors
 * production : chez Jean, l'écran doit rester un cadre posé au mur, sans une
 * seule inscription technique de plus que le strict nécessaire.
 */
object Environnement {

    /** Vrai sur la tablette de Jean, faux sur celle d'essai. */
    val estProduction: Boolean = BuildConfig.ENVIRONMENT == "production"

    /**
     * Ce qui s'affiche à côté du numéro de version : « rev47 » en production,
     * « rev47 · VALIDATION » ailleurs.
     */
    fun étiquetteVersion(): String =
        if (estProduction) BuildConfig.BUILD_REV
        else "${BuildConfig.BUILD_REV} · ${BuildConfig.ENVIRONMENT.uppercase()}"

    /**
     * Description complète, pour le journal technique et le panneau
     * d'administration : de quoi trancher en une ligne quand on relit une
     * trace sans se rappeler de quelle tablette elle vient.
     */
    fun description(): String =
        "${BuildConfig.ENVIRONMENT} · appareil=${BuildConfig.DEVICE_ID} · " +
            "appels=${BuildConfig.CALLS_COLLECTION} · kiosque=${BuildConfig.KIOSK_ENABLED}"
}
