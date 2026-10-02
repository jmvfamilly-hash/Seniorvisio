package com.iiifviewer

/**
 * Règles de politesse propres à certains serveurs. Wikimedia (Commons, Wikidata, Wikipédia) exige un User-Agent qui IDENTIFIE l'application
 * et son responsable ; un User-Agent de navigateur y reçoit des limites de débit (HTTP 429). Les autres serveurs gardent le leur.
 */
object HostEtiquette {
    /** Contact public de l'application, demandé par la politique de User-Agent de Wikimedia (jamais une adresse e-mail privée). */
    const val WIKIMEDIA_USER_AGENT = "VanGoghTimeline/1.0 (Android IIIF viewer; https://github.com/jmvfamilly-hash) IiifViewer/1.0"

    fun isWikimedia(url: String): Boolean {
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        return host.endsWith("wikimedia.org") || host.endsWith("wikidata.org") || host.endsWith("wikipedia.org")
    }

    fun userAgentFor(url: String, default: String): String = if (isWikimedia(url)) WIKIMEDIA_USER_AGENT else default
}
