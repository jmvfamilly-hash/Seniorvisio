package com.seniorvisio.core

import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * Le chargeur d'images de l'application, et son cache BORNÉ.
 *
 * ═══ POURQUOI IL EXISTE ═══
 *
 * Coil sait construire son propre chargeur tout seul, et c'est ce qui se
 * passait : la visionneuse prenait celui du contexte. Son cache mémoire est
 * alors dimensionné EN POURCENTAGE de la mémoire de l'appareil — un quart sur
 * une tablette ordinaire. Sur celle-ci, qui annonce 1896 Mo, cela autorise
 * près de cinq cents mégaoctets de bitmaps.
 *
 * Ce serait déjà beaucoup avec des vignettes. Ça ne l'est plus du tout depuis
 * que les photos sont rangées en pleine définition (voir VerificateurPhoto) :
 * la même règle appliquée à des images d'appareil laisse le cache grandir
 * jusqu'à ce que le système commence à tuer des services.
 *
 * Et ce projet connaît cette panne par cœur. Le tas natif est déjà monté à
 * 883 mégaoctets une fois, sur des vignettes, pour la même raison de fond :
 * depuis Android 8 les pixels vivent dans le tas NATIF tandis que le
 * ramasse-miettes se déclenche sur le tas JAVA, qui reste ici sous vingt
 * mégaoctets. Aucune collecte ne part, et rien ne vient donc borner ce qu'un
 * cache décide de garder. Il faut le lui dire.
 *
 * ═══ ET LE CACHE DISQUE EST COUPÉ ═══
 *
 * Il n'a aucun sens ici : on charge des FICHIERS déjà rangés sur la tablette
 * (voir RecueilStore). Un cache disque en ferait une seconde copie, pour
 * relire depuis le disque ce qui était déjà sur le disque.
 */
object ChargeurImages {

    /**
     * Le plafond du cache d'images, en octets.
     *
     * Soixante-quatre mégaoctets, et le calcul se tient : une photo affichée
     * à la définition de la dalle pèse environ huit mégaoctets en mémoire, et
     * la visionneuse n'en compose qu'une à la fois. Ce plafond garde donc
     * l'équivalent de huit affichages — largement de quoi revenir en arrière
     * sans redécoder, et vingt-cinq fois moins que ce que le défaut
     * autoriserait sur cette tablette.
     *
     * Les tuiles de Telephoto ne passent pas par ce cache : elles sont
     * décodées à part et bornées par ce qui est visible.
     *
     * Un Int et non un Long, parce que c'est ce que Coil attend. Soixante-
     * quatre mégaoctets tiennent très largement dans un entier signé ; c'est
     * d'ailleurs la raison pour laquelle cette signature peut se le permettre.
     */
    private const val PLAFOND_OCTETS = 64 * 1024 * 1024

    @Volatile private var chargeur: ImageLoader? = null

    /**
     * Le chargeur, construit une seule fois.
     *
     * Le contexte d'application et non celui reçu : ce chargeur vit aussi
     * longtemps que le processus, et retenir une Activity le ferait survivre
     * à l'écran qui l'a demandé — une fuite de mémoire pour un objet dont le
     * métier est justement d'en économiser.
     */
    fun pour(context: Context): ImageLoader {
        chargeur?.let { return it }
        return synchronized(this) {
            chargeur ?: ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizeBytes(PLAFOND_OCTETS)
                        .build()
                }
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
                .also { chargeur = it }
        }
    }

    /**
     * Ce que le cache occupe en ce moment, en mégaoctets, ou null s'il n'a
     * jamais servi.
     *
     * Publié dans la ligne de mesure mémoire (voir CallTrace.mesureMémoire).
     * Sans ce chiffre, une montée du résident ne dit pas SI elle vient des
     * images : on l'a supposé une fois, et supposer est exactement ce que ce
     * journal existe pour éviter.
     */
    fun tailleCacheMo(): Long? =
        chargeur?.memoryCache?.let { it.size / (1024L * 1024L) }
}
