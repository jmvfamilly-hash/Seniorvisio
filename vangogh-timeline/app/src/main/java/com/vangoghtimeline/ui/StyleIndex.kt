package com.vangoghtimeline.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.vangoghtimeline.iiif.Diag
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.PixelCodec
import com.vangoghtimeline.model.PixelTagger
import com.vangoghtimeline.model.PixelTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Index des couleurs des œuvres, sur l'appareil : pour chaque œuvre, noir et blanc / couleur et couleurs dominantes ([PixelTags]), calculés sur une
 * petite vignette (120 px) téléchargée par Coil — donc avec les mêmes politesses réseau que le reste (agent, cadence Wikimedia, nouvelles tentatives).
 * Persisté dans `style_index.txt` (une ligne « identifiant ⇥ codage ») : une œuvre n'est analysée qu'une fois.
 *
 * [version] augmente à chaque lot analysé : lue dans une composition, elle la relance (les filtres de couleur se mettent à jour en direct).
 */
object StyleIndex {
    private val map = ConcurrentHashMap<String, PixelTags>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var loaded = false

    var version by mutableStateOf(0)
        private set

    fun get(id: String): PixelTags? = map[id]

    fun indexedCount(artworks: List<Artwork>): Int = artworks.count { map.containsKey(it.id) }

    @Synchronized
    private fun ensureLoaded(file: File) {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) PixelCodec.decode(line.substring(tab + 1))?.let { map[line.substring(0, tab)] = it }
            }
        }.onFailure { Diag.warn("indexation", "lecture de l'index impossible : ${it.message}", file.path, key = "style-load") }
        version++
    }

    @Synchronized
    private fun save(file: File) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w -> map.forEach { (id, t) -> w.write(id + "\t" + PixelCodec.encode(t) + "\n") } }
            tmp.renameTo(file)
        }.onFailure { Diag.warn("indexation", "écriture de l'index impossible : ${it.message}", file.path, key = "style-save") }
    }

    /** Analyse, 3 à la fois, les œuvres pas encore indexées (dans l'ordre donné). S'arrête proprement si la coroutine est annulée. */
    suspend fun index(context: Context, loader: ImageLoader, artworks: List<Artwork>) {
        val file = File(context.filesDir, "style_index.txt")
        ensureLoaded(file)
        val todo = artworks.filter { !map.containsKey(it.id) && it.id !in failed }
        if (todo.isEmpty()) return
        val sem = Semaphore(3)
        var sinceSave = 0
        try {
            coroutineScope {
                todo.map { a ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val tags = analyze(context, loader, a)
                            if (tags == null) failed += a.id else map[a.id] = tags
                            val save = synchronized(this@StyleIndex) { ++sinceSave % 40 == 0 }
                            if (tags != null && (sinceSave % 10 == 0)) withContext(Dispatchers.Main) { version++ }
                            if (save) save(file)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            save(file)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { version++ }
        }
    }

    private suspend fun analyze(context: Context, loader: ImageLoader, a: Artwork): PixelTags? {
        val url = a.iiif.thumbnailUrlFor(120, 120) ?: return null
        val req = ImageRequest.Builder(context).data(url).size(96, 96).allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED).crossfade(false).build()
        val result = loader.execute(req) as? SuccessResult ?: return null
        val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24)
        small.getPixels(px, 0, 24, 0, 0, 24, 24)
        return PixelTagger.analyze(px)
    }
}
