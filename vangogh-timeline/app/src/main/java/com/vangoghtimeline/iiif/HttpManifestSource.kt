package com.vangoghtimeline.iiif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lit un manifeste par HTTP (Android/JVM). Seul fichier réseau du module. */
const val USER_AGENT = "VanGoghTimeline/1.0 (Android; IIIF viewer)"

class HttpManifestSource : ManifestSource {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/ld+json, application/json")
            // l'API AIC (derrière Cloudflare) répond 403 aux requêtes sans User-Agent identifiable
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} sur $url")
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }
}
