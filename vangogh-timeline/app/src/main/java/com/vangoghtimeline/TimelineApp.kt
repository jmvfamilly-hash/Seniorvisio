package com.vangoghtimeline

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.iiifviewer.DiskTileCache
import com.iiifviewer.HostEtiquette
import com.iiifviewer.HttpUpgrade
import com.vangoghtimeline.iiif.AIC_USER_AGENT
import com.vangoghtimeline.iiif.USER_AGENT
import okhttp3.OkHttpClient

/**
 * Chargeur d'images unique, réglé pour des vignettes IIIF :
 * - cache mémoire de 25 % de la RAM disponible à l'application : des dizaines de vignettes y tiennent largement ;
 * - cache disque de 100 Mo : la frise se rouvre sans réseau ;
 * - `respectCacheHeaders(false)` : beaucoup de serveurs IIIF répondent `no-cache` ou sans durée de validité ; une vignette
 *   d'œuvre, elle, ne change pas, et sans cela Coil la retélécharge à chaque ouverture.
 */
class TimelineApp : Application(), ImageLoaderFactory {
    /**
     * Cache disque des tuiles (les plus récemment utilisées d'abord, 256 Mo) : une œuvre déjà ouverte se rouvre sans retélécharger.
     * Dans `cacheDir` : le système peut le vider si la place manque.
     */
    val tileCache: DiskTileCache by lazy { DiskTileCache(cacheDir.resolve("iiif_tiles"), 256L * 1024 * 1024) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }

    private val wikimediaLock = Any()
    private var wikimediaNextSlot = 0L

    /** Espace d'au moins 250 ms les départs de requêtes vers Wikimedia (toutes les vignettes de la frise passent par ici). */
    private fun waitWikimediaSlot() {
        val wait = synchronized(wikimediaLock) {
            val now = System.currentTimeMillis()
            val start = maxOf(now, wikimediaNextSlot)
            wikimediaNextSlot = start + 250
            start - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("vangogh_thumbs"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .okHttpClient {
                // User-Agent explicite : sans lui, le serveur IIIF d'AIC (Cloudflare) répond 403 aux vignettes
                OkHttpClient.Builder().addInterceptor { chain ->
                    val original = chain.request()
                    // Android refuse le HTTP non chiffré : une vignette en http:// est demandée en https://
                    val request = if (original.url.scheme == "http" && HttpUpgrade.needsUpgrade(original.url.toString()))
                        original.newBuilder().url(original.url.newBuilder().scheme("https").build()).build() else original
                    val builder = request.newBuilder()
                        .header("User-Agent", HostEtiquette.userAgentFor(request.url.toString(), USER_AGENT))
                        .header("AIC-User-Agent", AIC_USER_AGENT)
                    // le Referer n'est utile qu'au serveur d'images de l'AIC : pas d'indication envoyée aux autres musées
                    if (request.url.host.endsWith("artic.edu")) builder.header("Referer", "https://www.artic.edu/")
                    val outgoing = builder.build()
                    // Wikimedia limite le débit : une file (250 ms entre deux départs) et UN nouvel essai après un 429 (Retry-After, 1 à 4 s)
                    if (!HostEtiquette.isWikimedia(outgoing.url.toString())) return@addInterceptor chain.proceed(outgoing)
                    try {
                        waitWikimediaSlot()
                        var response = chain.proceed(outgoing)
                        if (response.code == 429 || response.code == 503) {
                            val pause = (response.header("Retry-After")?.trim()?.toLongOrNull() ?: 2L).coerceIn(1L, 4L)
                            response.close()
                            Thread.sleep(pause * 1000)
                            waitWikimediaSlot()
                            response = chain.proceed(outgoing)
                        }
                        response
                    } catch (e: InterruptedException) {
                        throw java.io.InterruptedIOException("annulé")
                    }
                }.build()
            }
            .respectCacheHeaders(false)
            .build()
}
