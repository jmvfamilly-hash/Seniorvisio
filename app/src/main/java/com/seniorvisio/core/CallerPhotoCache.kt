package com.seniorvisio.core

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Écrit la photo de l'appelant (reçue en base64 depuis Firestore) sur le
 * disque plutôt que de la transporter via des extras d'Intent entre
 * CallListenerService, IncomingCallService et IncomingCallActivity.
 *
 * Constaté en usage réel : une photo pourtant modeste une fois encodée
 * (quelques centaines de kilooctets) suffit à dépasser la limite de
 * transaction Binder entre composants Android — bien plus stricte et
 * fragile qu'un document Firestore — et fait planter silencieusement le
 * démarrage du service, sans qu'aucun écran d'appel n'apparaisse jamais
 * (TransactionTooLargeException, invisible sans inspecter logcat). Un
 * chemin de fichier ne fait que quelques dizaines d'octets, quelle que soit
 * la taille réelle de la photo.
 *
 * Nom de fichier fixe, réécrit à chaque appel : pas de nettoyage à prévoir,
 * pas d'accumulation dans le cache.
 */
object CallerPhotoCache {
    private const val TAG = "CallerPhotoCache"
    private const val FILE_NAME = "caller_photo.jpg"

    /** Écrit la photo sur le disque et renvoie son chemin absolu, ou null si elle est absente/invalide. */
    fun save(context: Context, photoBase64: String?): String? {
        if (photoBase64.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
            val file = File(context.cacheDir, FILE_NAME)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Photo d'appelant mal encodée, ignorée", e)
            null
        }
    }
}
