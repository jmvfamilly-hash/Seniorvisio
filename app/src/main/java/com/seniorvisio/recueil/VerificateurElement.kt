package com.seniorvisio.recueil

import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Décide si CETTE tablette sait afficher un élément, en essayant réellement.
 *
 * ═══ POURQUOI LA VÉRIFICATION NE PEUT PAS SE FAIRE AILLEURS ═══
 *
 * Seule la tablette sait ce que la tablette sait afficher. Le navigateur du
 * proche peut parfaitement décoder une image que `BitmapFactory` refusera, et
 * l'inverse est vrai aussi — nous venons d'en faire les frais dans ce sens-là
 * précisément : des photos HEIC que Chrome sur Android ne sait pas ouvrir,
 * alors qu'Android le sait depuis la version 9.
 *
 * Un contrôle fondé sur l'extension ou sur le type MIME serait donc une
 * supposition déguisée en vérification. On décode pour de bon, et ce qui est
 * déclaré PRÊT l'est parce qu'on vient de le faire.
 *
 * ═══ ET ON RÉENCODE DANS LA FOULÉE ═══
 *
 * Le fichier rangé n'est pas l'original mais une image à la définition de la
 * dalle. Deux raisons : une photo de téléphone moderne fait quatre fois la
 * définition de l'écran, et la redécoder à chaque affichage coûterait une
 * seconde d'attente sur une tablette ancienne — celle du banc d'essai met
 * déjà 200 ms à transcrire 30 ms de son.
 *
 * Le réencodage est fait UNE FOIS, à l'installation, quand personne n'attend.
 */
interface VerificateurElement {
    /**
     * Vérifie et range. Rend l'élément mis à jour : PRÊT avec son fichier
     * local, ou REFUSÉ avec une cause écrite POUR LE PROCHE — c'est lui qui
     * la lira dans le PWA, pas un développeur dans un journal.
     */
    fun vérifier(element: Element, téléchargé: File, dossier: File): Element
}

/**
 * Le seul type pris en charge aujourd'hui.
 *
 * ═══ LA PHOTO EST RANGÉE TELLE QU'ELLE EST ARRIVÉE ═══
 *
 * Elle était réduite à la définition de la dalle, puis réencodée en JPEG. Ce
 * n'est plus le cas, et c'est la condition du zoom : on ne peut pas agrandir
 * ce qui a été jeté à l'installation. Demandé en ces termes — « les photos
 * doivent garder leur résolution initiale pour permettre le zoom et la
 * navigation ».
 *
 * Ce que la réduction protégeait est protégé autrement. Elle évitait de
 * charger cinquante mégaoctets de pixels pour une dalle qui en affiche deux ;
 * c'est désormais le décodage par tuiles de la visionneuse qui s'en charge, en
 * ne lisant que la portion visible à la finesse où on la regarde (voir
 * VisionneusePhotos). Le coût se déplace de la mémoire vive vers le disque, et
 * le disque est déjà borné en amont : le PWA refuse tout fichier au-delà de
 * vingt-cinq mégaoctets.
 *
 * ═══ ET LA VÉRIFICATION, ELLE, RESTE ENTIÈRE ═══
 *
 * C'est la raison d'être de cette classe, et elle ne change pas : rien
 * n'atteint l'écran de Jean sans que la tablette ait prouvé savoir le décoder.
 * Un proche — ou n'importe qui sachant écrire dans ce Firestore — ne dépose
 * pas ici un fichier qui ferait tomber l'écran d'appel.
 *
 * La preuve se fait sur un décodage RÉDUIT et non sur l'image entière. Un
 * échantillon suffit à établir que le décodeur accepte ce fichier, et décoder
 * en pleine taille pour le seul plaisir de le vérifier rouvrirait exactement
 * le risque de mémoire qu'on vient de fermer.
 *
 * ═══ UN EFFET DE BORD QUI CORRIGE UN DÉFAUT ═══
 *
 * Réencoder perdait les métadonnées EXIF, dont l'orientation. Une photo prise
 * en portrait était rangée sans son quart de tour, et rien ne le rétablissait
 * à l'affichage : elle apparaissait couchée. En copiant le fichier d'origine,
 * l'orientation voyage avec lui et Coil l'applique.
 *
 * @param côtéMax définition de la dalle. Ne sert plus à ranger, seulement à
 *   dimensionner le décodage de contrôle : inutile d'en lire plus que ce
 *   qu'un écran peut montrer pour prouver qu'un fichier se décode.
 */
class VerificateurPhoto(private val côtéMax: Int) : VerificateurElement {

    override fun vérifier(element: Element, téléchargé: File, dossier: File): Element {
        if (téléchargé.length() == 0L) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Fichier vide — la photo n'a peut-être pas fini d'être envoyée.",
            )
        }

        // Première passe sans allouer l'image : ses dimensions suffisent à
        // dimensionner le contrôle. Décoder une photo de 12 Mpx en pleine
        // taille sur une tablette ancienne est le meilleur moyen de manquer de
        // mémoire — précisément sur les fichiers qu'on cherche à traiter.
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(téléchargé.absolutePath, mesure)
        if (mesure.outWidth <= 0 || mesure.outHeight <= 0) {
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Format non reconnu par la tablette.",
            )
        }

        // Le décodage de contrôle. Son résultat n'est pas gardé : on ne veut
        // savoir qu'une chose, est-ce que ce fichier produit des pixels.
        val options = BitmapFactory.Options().apply {
            inSampleSize = facteurDeRéduction(mesure.outWidth, mesure.outHeight, côtéMax)
        }
        val témoin = try {
            BitmapFactory.decodeFile(téléchargé.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            // Attrapé explicitement : ce n'est pas une Exception, un catch
            // ordinaire le laisserait passer et emporterait l'installation
            // entière pour une seule photo trop lourde.
            Log.w(TAG, "Mémoire insuffisante pour ${element.id}", e)
            return element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "Photo trop lourde pour cette tablette.",
            )
        } ?: return element.copy(
            état = ÉtatElement.REFUSÉ,
            cause = "Image illisible — fichier probablement abîmé.",
        )
        // Rendu tout de suite : il a fini son office, et le garder le temps de
        // la copie ferait cohabiter sans raison des pixels et un fichier.
        témoin.recycle()

        return try {
            // Le nom garde son extension .jpg quel que soit le format réel du
            // fichier. C'est délibéré : plusieurs endroits cherchent
            // « <id>.jpg » pour savoir si un élément est installé (voir
            // RecueilStore.estInstallé), et le décodeur reconnaît un format à
            // son contenu, jamais à son nom.
            val nom = "${element.id}.jpg"
            téléchargé.copyTo(File(dossier, nom), overwrite = true)
            element.copy(état = ÉtatElement.PRÊT, cause = null, fichierLocal = nom)
        } catch (e: Exception) {
            Log.e(TAG, "Rangement impossible pour ${element.id}", e)
            element.copy(
                état = ÉtatElement.REFUSÉ,
                cause = "La tablette n'a pas pu la ranger (${e.javaClass.simpleName}).",
            )
        }
    }

    /**
     * Puissance de deux la plus grande qui garde l'image au-dessus de la
     * définition voulue. `inSampleSize` n'accepte que ça : une valeur
     * intermédiaire est arrondie à la puissance inférieure, donc autant la
     * calculer franchement.
     */
    private fun facteurDeRéduction(largeur: Int, hauteur: Int, cible: Int): Int {
        var facteur = 1
        while (maxOf(largeur, hauteur) / (facteur * 2) >= cible) facteur *= 2
        return facteur
    }

    private companion object {
        const val TAG = "VerificateurPhoto"
    }
}

/**
 * Ce que la tablette ne sait pas encore afficher — et qui le DIT.
 *
 * Déclaré plutôt qu'absent, et c'est le point : un recueil contenant une
 * vidéo doit pouvoir être installé, décrit, et refuser cet élément-là avec
 * une phrase lisible par le proche. Sans cette classe, un type inconnu
 * produirait soit une exception, soit un élément resté À_VÉRIFIER pour
 * toujours — un recueil bloqué en « installation en cours », sans que rien
 * ne dise pourquoi.
 *
 * Le jour où la vidéo arrive, on remplace cette instance par un vrai
 * vérificateur. Rien d'autre ne bouge.
 */
class VerificateurNonPrisEnCharge(private val type: TypeElement) : VerificateurElement {
    override fun vérifier(element: Element, téléchargé: File, dossier: File): Element =
        element.copy(
            état = ÉtatElement.REFUSÉ,
            cause = "Les éléments de type « ${type.étiquette} » ne sont pas encore " +
                "affichables sur la tablette.",
        )
}

/** À qui confier quoi. Unique table de correspondance type → vérificateur. */
fun vérificateurPour(type: TypeElement, côtéMax: Int): VerificateurElement = when (type) {
    TypeElement.PHOTO -> VerificateurPhoto(côtéMax)
    else -> VerificateurNonPrisEnCharge(type)
}
