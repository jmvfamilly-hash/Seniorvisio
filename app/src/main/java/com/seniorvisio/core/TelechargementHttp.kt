package com.seniorvisio.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Télécharge un fichier en suivant les redirections à la main.
 *
 * ═══ POURQUOI SUIVRE LES REDIRECTIONS SOI-MÊME ═══
 *
 * `instanceFollowRedirects` ne franchit pas un changement de protocole, et
 * ne franchit pas non plus certains sauts entre hôtes. Or c'est exactement ce
 * que font les deux sources dont dépend cette tablette : une release GitHub
 * renvoie vers un domaine de stockage, et une URL de téléchargement Firebase
 * Storage peut en faire autant. S'en remettre au comportement par défaut,
 * c'est obtenir une page de redirection de quelques centaines d'octets au
 * lieu du fichier, et ne s'en apercevoir qu'au moment de l'ouvrir.
 *
 * ═══ EXTRAIT DE DeviceStatusReporter, PAS RÉÉCRIT ═══
 *
 * Ce code téléchargeait les mises à jour depuis des semaines. L'installation
 * des recueils a exactement le même besoin, et la tentation était de le
 * recopier en changeant le nom du fichier de sortie. Deux copies d'un même
 * traitement réseau divergent toujours — l'une gagne un correctif que l'autre
 * n'a pas, et c'est celle qu'on ne regarde pas qui tombe en panne.
 */
object TelechargementHttp {

    /**
     * @param quoi nommé dans les messages d'erreur : « APK », « photo »…
     *   Sans ça, un échec dit seulement qu'un téléchargement a raté, ce qui
     *   ne permet pas de savoir lequel quand deux chemins l'utilisent.
     */
    fun vers(url: String, destination: File, quoi: String): File {
        var courante = URL(url)
        var redirections = 0
        while (true) {
            val connexion = courante.openConnection() as HttpURLConnection
            connexion.instanceFollowRedirects = false
            connexion.connectTimeout = CONNEXION_MS
            connexion.readTimeout = LECTURE_MS
            connexion.setRequestProperty("User-Agent", AGENT)
            connexion.connect()
            val code = connexion.responseCode
            if (code in 300..399) {
                val destinationRedirigée = connexion.getHeaderField("Location")
                connexion.disconnect()
                if (destinationRedirigée == null) {
                    throw IOException("Redirection sans en-tête Location (code $code) — $quoi")
                }
                redirections++
                if (redirections > MAX_REDIRECTIONS) {
                    throw IOException("Trop de redirections lors du téléchargement — $quoi")
                }
                courante = URL(courante, destinationRedirigée)
                continue
            }
            if (code !in 200..299) {
                connexion.disconnect()
                throw IOException("Téléchargement refusé par le serveur (code HTTP $code) — $quoi")
            }
            connexion.inputStream.use { entrée ->
                destination.outputStream().use { sortie -> entrée.copyTo(sortie) }
            }
            connexion.disconnect()
            return destination
        }
    }

    private const val CONNEXION_MS = 15_000
    private const val LECTURE_MS = 30_000
    private const val MAX_REDIRECTIONS = 5
    private const val AGENT = "SeniorVisio-Tablette"
}
