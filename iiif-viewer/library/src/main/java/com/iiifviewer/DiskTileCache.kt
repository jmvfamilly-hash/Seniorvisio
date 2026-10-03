package com.iiifviewer

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache disque des tuiles (et images ordinaires) déjà vues : une œuvre déjà ouverte se rouvre sans retélécharger, même hors ligne.
 *
 * - **Les plus récentes d'abord** : au-delà de [maxBytes], les entrées utilisées le plus ANCIENNEMENT sont supprimées (date du fichier,
 *   remise à jour à chaque lecture), jusqu'à repasser à 90 % du plafond.
 * - Les textes (manifestes, `info.json`) ont en plus une durée de vie ([TEXT_TTL_MS], 30 jours) : on les relit après.
 * - Une entrée plus grosse que [MAX_ENTRY_BYTES] n'est pas gardée (une image ordinaire de 80 Mo ne doit pas chasser tout le reste).
 * - Écriture atomique (fichier temporaire puis renommage) : jamais de fichier tronqué ; une lecture qui échoue compte comme absente.
 *
 * Sûr entre fils. Pur JVM : testé sur la JVM. Le dossier devrait être le `cacheDir` d'Android : le système peut le vider si la place manque.
 */
class DiskTileCache(
    private val dir: File,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val total = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    init {
        dir.mkdirs()
        total.set(dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.sumOf { it.length() } ?: 0L)
    }

    private fun fileFor(key: String) = File(dir, sha1(key))

    /** Octets d'une tuile/image gardée, ou `null`. Une lecture remet l'entrée « au plus récent ». */
    fun get(url: String): ByteArray? = read("b:$url", maxAgeMs = Long.MAX_VALUE, touch = true)

    fun put(url: String, bytes: ByteArray) = write("b:$url", bytes)

    /** Texte (manifeste, `info.json`) gardé depuis moins de [maxAgeMs] ; la lecture ne le rajeunit PAS (sa durée de vie court depuis l'écriture). */
    fun getText(url: String, maxAgeMs: Long = TEXT_TTL_MS): String? = read("t:$url", maxAgeMs, touch = false)?.decodeToString()

    fun putText(url: String, text: String) = write("t:$url", text.encodeToByteArray())

    fun remove(url: String) {
        synchronized(lock) {
            for (k in listOf("b:$url", "t:$url")) {
                val f = fileFor(k)
                if (f.exists()) { val len = f.length(); if (f.delete()) total.addAndGet(-len) }
            }
        }
    }

    private fun read(key: String, maxAgeMs: Long, touch: Boolean): ByteArray? = synchronized(lock) {
        val f = fileFor(key)
        val bytes = if (f.exists() && clock() - f.lastModified() <= maxAgeMs) runCatching { f.readBytes() }.getOrNull() else null
        if (bytes == null) { missCount.incrementAndGet(); return null }
        if (touch) f.setLastModified(clock())
        hitCount.incrementAndGet()
        bytes
    }

    private fun write(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val target = fileFor(key)
                val old = if (target.exists()) target.length() else 0L
                val tmp = File(dir, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                target.setLastModified(clock())
                total.addAndGet(bytes.size - old)
            }
            if (total.get() > maxBytes) trim()
        }
    }

    /** Supprime les entrées les plus anciennement utilisées jusqu'à 90 % du plafond. */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var size = files.sumOf { it.length() }
        val target = (maxBytes * 0.9).toLong()
        for (f in files.sortedBy { it.lastModified() }) {
            if (size <= target) break
            val len = f.length()
            if (f.delete()) size -= len
        }
        total.set(size)
    }

    /** Octets gardés. */
    val bytes: Long get() = total.get()
    val hits: Long get() = hitCount.get()
    val misses: Long get() = missCount.get()

    /** « 123 fichiers, 45 Mo sur 256 Mo, 80 % de réussites (400 lectures) » : pour le rapport d'anomalies. */
    fun stats(): String {
        val files = dir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0
        val reads = hits + misses
        val rate = if (reads > 0) "${hits * 100 / reads} % de réussites ($reads lectures)" else "aucune lecture"
        return "$files fichiers, ${bytes / (1024 * 1024)} Mo sur ${maxBytes / (1024 * 1024)} Mo, $rate"
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val TEXT_TTL_MS = 30L * 24 * 3_600_000
        const val MAX_ENTRY_BYTES = 40 * 1024 * 1024
    }
}
