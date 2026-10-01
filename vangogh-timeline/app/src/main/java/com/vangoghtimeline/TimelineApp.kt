package com.vangoghtimeline

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
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
                        .header("User-Agent", USER_AGENT)
                        .header("AIC-User-Agent", AIC_USER_AGENT)
                    // le Referer n'est utile qu'au serveur d'images de l'AIC : pas d'indication envoyée aux autres musées
                    if (request.url.host.endsWith("artic.edu")) builder.header("Referer", "https://www.artic.edu/")
                    chain.proceed(builder.build())
                }.build()
            }
            .respectCacheHeaders(false)
            .build()
}
