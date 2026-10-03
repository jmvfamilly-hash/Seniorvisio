package com.vangoghtimeline.iiif

import com.iiifviewer.HostEtiquette
import com.iiifviewer.HttpUpgrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 VanGoghTimeline/1.0"
const val AIC_USER_AGENT = "VanGoghTimeline/1.0 (Android)"

/**
 * Android interdit le HTTP non chiffré : une adresse `http://` est réécrite en `https://` (voir [HttpUpgrade]), et la réécriture est
 * consignée une fois par serveur (un serveur qui n'accepterait pas le HTTPS verrait alors son échec expliqué dans le journal).
 */
internal fun secured(url: String): String {
    val secure = HttpUpgrade.secure(url)
    if (secure != url) Diag.info("réseau", "adresse http:// réécrite en https:// pour ${Diag.hostOf(url)} (HTTP non chiffré interdit sur Android)", url, key = "https-upgrade|${Diag.hostOf(url)}")
    return secure
}

/** User-Agent sobre : variante de repli quand un serveur refuse celui d'un navigateur (voir [UniverseLoader]). */
const val PLAIN_USER_AGENT = "VanGoghTimeline/1.0"

/**
 * Lit un manifeste ou une réponse d'API par HTTP (Android/JVM). Seul fichier réseau du module.
 *
 * Une réponse en erreur lève une [IOException] qui dit TOUT ce qu'on sait : code, URL, serveur, type, début du corps (300 caractères, 800 pour du JSON).
 * Un « HTTP 410 » seul ne dit pas si c'est une API retirée ou un pare-feu qui refuse : le corps le dit.
 */
class HttpManifestSource(private val userAgent: String = USER_AGENT) : ManifestSource {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(secured(url)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/ld+json, application/json")
            // l'API AIC (derrière Cloudflare) répond 403 aux requêtes sans User-Agent identifiable
            conn.setRequestProperty("User-Agent", HostEtiquette.userAgentFor(url, userAgent))   // Wikimedia : User-Agent qui identifie l'application
            conn.setRequestProperty("AIC-User-Agent", AIC_USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException(describeError(code, url, conn))
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, url: String, conn: HttpURLConnection): String {
        val body = try {
            // un corps JSON porte souvent l'explication complète (API retirée…) : on en garde davantage
            val limit = if (conn.contentType?.contains("json", ignoreCase = true) == true) 800 else 300
            conn.errorStream?.use { it.readBytes().take(limit).toByteArray().decodeToString() }?.replace(Regex("\\s+"), " ")?.trim()
        } catch (e: Exception) { null }
        val details = listOfNotNull(
            conn.getHeaderField("Server")?.let { "serveur : $it" },
            conn.contentType?.let { "type : $it" },
            conn.getHeaderField("Location")?.let { "redirection : $it" },
            body?.takeIf { it.isNotEmpty() }?.let { "corps : « $it »" },
        )
        return "HTTP $code sur $url" + if (details.isEmpty()) "" else " — " + details.joinToString(" · ")
    }
}

/** Accès « léger » à une image : requête `Range: bytes=0-0`, on ne lit que le code et le type de contenu. */
class HttpImageReachability : ImageReachability {
    override suspend fun check(url: String): Reach = withContext(Dispatchers.IO) {
        val conn = URL(secured(url)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", HostEtiquette.userAgentFor(url, USER_AGENT))
            conn.setRequestProperty("AIC-User-Agent", AIC_USER_AGENT)
            Reach(conn.responseCode, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }
}
