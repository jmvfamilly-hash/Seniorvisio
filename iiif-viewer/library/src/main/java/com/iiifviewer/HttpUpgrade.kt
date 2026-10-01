package com.iiifviewer

/**
 * Android interdit le trafic HTTP non chiffré (« Cleartext HTTP traffic to … not permitted ») alors que beaucoup de manifestes IIIF
 * donnent encore des adresses `http://` pour leurs services d'images. Presque tous répondent aussi en HTTPS : on réécrit donc le
 * schéma, sauf pour les adresses locales (développement) qui n'ont pas de certificat.
 *
 * Pur Kotlin : testé sur la JVM.
 */
object HttpUpgrade {
    /** `http://hôte/…` → `https://hôte/…` ; toute autre adresse est rendue telle quelle. */
    fun secure(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val rest = url.substring("http://".length)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        if (isLocal(host)) return url
        return "https://$rest"
    }

    /** Vrai si [url] serait réécrite par [secure]. */
    fun needsUpgrade(url: String): Boolean = secure(url) != url

    private fun isLocal(host: String): Boolean =
        host == "localhost" || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("127.") || Regex("""172\.(1[6-9]|2\d|3[01])\..*""").matches(host)
}
