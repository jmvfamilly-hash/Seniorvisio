package com.vangoghtimeline.demo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.iiifviewer.HttpIiifSources
import com.iiifviewer.IiifImageInfo
import com.iiifviewer.IiifSources
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sources du visualiseur pour la frise : les adresses `demo:vangogh/<id>` sont servies HORS LIGNE par un générateur
 * d'images pyramidales (un ensemble de Mandelbrot par œuvre, calculé tuile par tuile avec le découpage exact d'un serveur
 * IIIF) ; toute autre adresse part sur le réseau comme d'habitude. Le zoom profond est donc démontrable sans serveur.
 */
class DemoIiifSources(private val network: IiifSources = HttpIiifSources()) : IiifSources {

    override suspend fun loadInfo(infoUrl: String): IiifImageInfo =
        if (DemoTileUrl.isDemo(infoUrl)) {
            IiifImageInfo(
                baseUri = DemoTileUrl.base(infoUrl), width = WIDTH, height = HEIGHT, tileSize = 256,
                scaleFactors = listOf(1, 2, 4, 8, 16, 32, 64),
            )
        } else network.loadInfo(infoUrl)

    override suspend fun load(url: String): ImageBitmap {
        if (!DemoTileUrl.isDemo(url)) return network.load(url)
        val tile = DemoTileUrl.parseTile(url) ?: throw IllegalArgumentException("tuile de démonstration invalide: $url")
        return render(tile)
    }

    /** Appelé par le gestionnaire sur un thread d'arrière-plan : calcul pur, pas d'accès à l'UI. */
    private fun render(t: DemoTile): ImageBitmap {
        val step = t.width / t.outWidth.toFloat()                           // pixels de l'image par pixel de la tuile
        val outW = t.outWidth
        val outH = max(1, (t.height / step).roundToInt())
        val hue = (t.id.hashCode() and 0xFFFF) / 65535f * 2f * PI.toFloat()  // une palette par œuvre
        val dx = 3.2 / WIDTH
        val maxIter = (90 + 55 * (ln(WIDTH / (256.0 * step)) / ln(2.0))).roundToInt().coerceAtLeast(60)
        val px = IntArray(outW * outH)
        for (j in 0 until outH) {
            val ci = -1.2 + (t.y + (j + 0.5) * step) * dx
            for (i in 0 until outW) {
                val cr = -2.2 + (t.x + (i + 0.5) * step) * dx
                var zr = 0.0
                var zi = 0.0
                var n = 0
                var m = 0.0
                while (n < maxIter) {
                    m = zr * zr + zi * zi
                    if (m > 256.0) break
                    val tmp = zr * zr - zi * zi + cr
                    zi = 2 * zr * zi + ci
                    zr = tmp
                    n++
                }
                px[j * outW + i] = if (n >= maxIter) 0xFF080A10.toInt() else {
                    val s = n + 1 - ln(ln(m) / ln(2.0)) / ln(2.0)
                    val a = (0.11 * s).toFloat()
                    val r = (128 + 127 * sin(a + 4.2f + hue)).toInt()
                    val g = (128 + 127 * sin(a + 2.4f + hue)).toInt()
                    val b = (128 + 127 * sin(a + 0.6f + hue)).toInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            .apply { setPixels(px, 0, outW, 0, 0, outW, outH) }
            .asImageBitmap()
    }

    private companion object {
        const val WIDTH = 16384
        const val HEIGHT = 12288
    }
}
