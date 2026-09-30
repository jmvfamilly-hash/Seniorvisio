package com.iiifviewer

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Seul fichier dépendant d'Android (implémentation `androidMain` de [IiifSources] en KMP).
 */
class HttpIiifSources : IiifSources {

    override suspend fun load(url: String): ImageBitmap = fetch(url) { stream ->
        val bitmap = BitmapFactory.decodeStream(stream) ?: throw IOException("Décodage impossible: $url")
        bitmap.asImageBitmap()
    }

    override suspend fun loadInfo(infoUrl: String): IiifImageInfo {
        val json = JSONObject(fetch(infoUrl) { it.readBytes().decodeToString() })
        val base = json.optString("id").ifEmpty { json.optString("@id") }
            .ifEmpty { infoUrl.removeSuffix("/info.json") }
            .trimEnd('/')
        val width = json.getInt("width")
        val height = json.getInt("height")

        val tiles = json.optJSONArray("tiles")?.optJSONObject(0)
        val tileSize = tiles?.optInt("width", 256) ?: 256
        val factors = tiles?.optJSONArray("scaleFactors")
            ?.let { arr -> List(arr.length()) { arr.getInt(it) } }
            ?.sorted()
            ?: generateSequence(1) { it * 2 }.takeWhile { it == 1 || width / it >= tileSize }.toList()
        return IiifImageInfo(base, width, height, tileSize, factors)
    }

    /**
     * GET annulable : HttpURLConnection bloque un thread et ignore l'annulation de la coroutine.
     * Un « veilleur » frère coupe donc la connexion dès que la coroutine est annulée, ce qui fait
     * échouer la lecture bloquée — c'est ce qui rend l'annulation agressive du TileManager réelle.
     */
    private suspend fun <T> fetch(url: String, read: (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            coroutineScope {
                val watchdog = launch {
                    try { awaitCancellation() } finally { conn.disconnect() }
                }
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} sur $url")
                    conn.inputStream.use(read)
                } finally {
                    watchdog.cancel()
                }
            }
        }
}

fun defaultIiifSources(): IiifSources = HttpIiifSources()
