package com.iiifviewer

/**
 * Cache LRU minimal en Kotlin commun (`android.util.LruCache` n'existe pas en KMP).
 * Non thread-safe : à n'utiliser que depuis le contexte mono-thread du [TileManager].
 */
class LruCache<K : Any, V : Any>(private val maxEntries: Int) {
    private val map = LinkedHashMap<K, V>()

    init { require(maxEntries > 0) }

    /** Lecture qui marque l'entrée comme « récemment utilisée ». */
    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value // réinsérée en fin = la plus récente
        return value
    }

    /** Lecture sans effet sur l'ordre d'éviction. */
    fun peek(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) {
            val eldest = map.keys.iterator()
            eldest.next()
            eldest.remove() // la plus ancienne est en tête
        }
    }

    fun clear() = map.clear()
}
