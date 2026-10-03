package com.iiifviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Seul fichier dépendant d'Android (implémentation `androidMain` de [IiifSources] en KMP).
 */
class HttpIiifSources(
    /** Cache disque des tuiles, images et `info.json` déjà vus ; `null` = pas de cache. */
    private val cache: DiskTileCache? = null,
) : IiifSources {

    override suspend fun load(url: String): ImageBitmap {
        StaticImageUrl.parse(url)?.let { return loadStaticTile(it) }
        return loadRemote(url)
    }

    private suspend fun loadRemote(url: String): ImageBitmap {
        cache?.get(url)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it.asImageBitmap() }
            cache?.remove(url)   // entrée illisible : on la jette et on retélécharge
        }
        val bytes = fetch(url) { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Décodage impossible: $url")
        cache?.put(url, bytes)
        return bitmap.asImageBitmap()
    }

    /** Texte d'un manifeste ou d'un `info.json` : du cache s'il a moins de 30 jours, sinon du réseau (puis mis en cache). */
    private suspend fun fetchText(url: String): String {
        cache?.getText(url)?.let { return it }
        val text = fetch(url) { it.readBytes().decodeToString() }
        cache?.putText(url, text)
        return text
    }

    override suspend fun loadInfo(infoUrl: String): IiifImageInfo {
        // `static:{url}` : une image ordinaire donnée directement (pas de manifeste à lire)
        StaticImageUrl.imageUrlOf(infoUrl)?.let { return staticInfo(IiifManifestResolver.StaticImage(it, null, null)) }
        val text = fetchText(infoUrl)
        // L'URL peut être celle d'un MANIFESTE (galerie, frise…) : on en tire le service d'image de la première page,
        // puis on lit son info.json.
        IiifManifestResolver.serviceIdOf(text)?.let { return loadInfo(IiifManifestResolver.infoUrlFor(it)) }
        // Pas de service IIIF : une image ordinaire (JPEG/PNG). On la découpe nous-mêmes en tuiles.
        IiifManifestResolver.staticImageOf(text)?.let { return staticInfo(it) }
        val json = JSONObject(text)
        val base = json.optString("id").ifEmpty { json.optString("@id") }
            .ifEmpty { infoUrl.removeSuffix("/info.json") }
            .trimEnd('/')
        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        if (width <= 0 || height <= 0) throw IOException("Pas d'image IIIF exploitable : $infoUrl")

        val tiles = json.optJSONArray("tiles")?.optJSONObject(0)
        val tileSize = tiles?.optInt("width", 256) ?: 256
        val factors = tiles?.optJSONArray("scaleFactors")
            ?.let { arr -> List(arr.length()) { arr.getInt(it) } }
            ?.sorted()
            ?: generateSequence(1) { it * 2 }.takeWhile { it == 1 || width / it >= tileSize }.toList()
        return IiifImageInfo(HttpUpgrade.secure(base), width, height, tileSize, factors)
    }

    // ── Images sans service IIIF ─────────────────────────────────────────────────
    private class StaticImage(val decoder: BitmapRegionDecoder, val width: Int, val height: Int)

    private val staticImages = LinkedHashMap<String, StaticImage>()
    private val staticLock = Mutex()

    /** Au plus 2 images ordinaires en mémoire (octets compressés + décodeur par région) ; téléchargées une seule fois. */
    private suspend fun staticImage(url: String): StaticImage = staticLock.withLock {
        staticImages[url]?.let { return@withLock it }
        val bytes = cache?.get(url) ?: fetch(url) { it.readBytes() }.also { cache?.put(url, it) }
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            ?: throw IOException("Décodage impossible: $url")
        val image = StaticImage(decoder, decoder.width, decoder.height)
        staticImages[url] = image
        while (staticImages.size > 2) staticImages.remove(staticImages.keys.first())
        image
    }

    private suspend fun staticInfo(image: IiifManifestResolver.StaticImage): IiifImageInfo {
        val decoded = staticImage(image.url)
        // les dimensions réelles du fichier font foi : une région hors fichier ferait échouer le décodage
        val width = decoded.width
        val height = decoded.height
        val tileSize = 512
        val factors = generateSequence(1) { it * 2 }.takeWhile { it == 1 || width / it >= tileSize }.toList()
        return IiifImageInfo(StaticImageUrl.baseUriOf(image.url), width, height, tileSize, factors)
    }

    private suspend fun loadStaticTile(req: StaticImageUrl.Request): ImageBitmap {
        val image = staticImage(req.imageUrl)
        return withContext(Dispatchers.Default) {
            val region = Rect(req.x, req.y, minOf(req.x + req.width, image.width), minOf(req.y + req.height, image.height))
            if (region.width() <= 0 || region.height() <= 0) throw IOException("Région hors image")
            val options = BitmapFactory.Options().apply { inSampleSize = StaticImageUrl.sampleSizeFor(region.width(), req.outputWidth) }
            // BitmapRegionDecoder n'est pas thread-safe : un décodage à la fois par image
            val bitmap = synchronized(image.decoder) { image.decoder.decodeRegion(region, options) }
                ?: throw IOException("Décodage impossible: ${req.imageUrl}")
            val outW = req.outputWidth.coerceAtLeast(1)
            val outH = (bitmap.height.toLong() * outW / bitmap.width).toInt().coerceAtLeast(1)
            (if (bitmap.width == outW) bitmap else Bitmap.createScaledBitmap(bitmap, outW, outH, true)).asImageBitmap()
        }
    }

    /** Limite de débit ou indisponibilité passagère (429, 503) : la lecture est retentée après la pause demandée par le serveur. */
    private class RetryAfter(val code: Int, val url: String, val waitMs: Long) : IOException("HTTP $code sur $url")

    /** Au plus 3 essais pour un 429/503 (pause du `Retry-After` du serveur, sinon 2 s, 4 s) ; l'erreur finale reste « HTTP 429 sur … ». */
    private suspend fun <T> fetch(url: String, read: (java.io.InputStream) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(url, read)
            } catch (e: RetryAfter) {
                if (attempt >= 2) throw IOException("HTTP ${e.code} sur ${e.url}")
                kotlinx.coroutines.delay(e.waitMs * (attempt + 1))
                attempt++
            }
        }
    }

    /**
     * GET annulable : HttpURLConnection bloque un thread et ignore l'annulation de la coroutine.
     * Un « veilleur » frère coupe donc la connexion dès que la coroutine est annulée, ce qui fait
     * échouer la lecture bloquée — c'est ce qui rend l'annulation agressive du TileManager réelle.
     */
    private suspend fun <T> fetchOnce(url: String, read: (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val conn = URL(HttpUpgrade.secure(url)).openConnection() as HttpURLConnection   // Android refuse le HTTP non chiffré
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            // certains serveurs (AIC derrière Cloudflare) refusent en 403 les requêtes sans User-Agent identifiable ;
            // Wikimedia, lui, exige un User-Agent qui identifie l'application (sinon limites de débit)
            conn.setRequestProperty("User-Agent", HostEtiquette.userAgentFor(url, BROWSER_USER_AGENT))
            conn.setRequestProperty("AIC-User-Agent", "IiifViewer/1.0 (Android)")
            conn.setRequestProperty("Referer", "https://${URL(url).host}/")
            conn.setRequestProperty("Accept", "application/json, image/*, */*")
            coroutineScope {
                val watchdog = launch {
                    try { awaitCancellation() } finally { conn.disconnect() }
                }
                try {
                    val code = conn.responseCode
                    if (code == 429 || code == 503) {
                        val wait = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1, 5)?.times(1000) ?: 2_000L
                        throw RetryAfter(code, url, wait)
                    }
                    if (code !in 200..299) throw IOException("HTTP $code sur $url")
                    conn.inputStream.use(read)
                } finally {
                    watchdog.cancel()
                }
            }
        }
}

/** UA de navigateur : le serveur IIIF d'AIC (Cloudflare) refuse en 403 les clients HTTP génériques. */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 IiifViewer/1.0"

fun defaultIiifSources(): IiifSources = HttpIiifSources()
