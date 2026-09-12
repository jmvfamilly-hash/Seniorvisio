package com.seniorvisio.core

import java.nio.ByteBuffer

/**
 * Tampon circulaire d'octets, écrit depuis un fil temps réel et lu par un
 * autre, **sans allouer une seule fois du côté de l'écriture**.
 *
 * ═══ Ce qu'il sert à éviter ═══
 *
 * Le rappel audio de WebRTC est appelé cent fois par seconde et par piste. Il
 * y allouait jusqu'ici un tableau neuf à chaque bloc. Ce n'est pas énorme —
 * de l'ordre de sept cents kilo-octets par seconde pour deux pistes — mais ce
 * n'est pas le volume qui compte ici : c'est QUI paie le ramassage. Une pause
 * du ramasse-miettes tombe sur le fil qui alloue, et ce fil-là a dix
 * millisecondes pour livrer le bloc suivant au haut-parleur.
 *
 * L'écriture ne fait donc plus qu'une recopie d'octets dans un tableau
 * préalloué une fois pour toutes.
 *
 * ═══ Ce qu'il ne prétend PAS faire ═══
 *
 * Il ne supprime pas toute allocation de la chaîne, et il ne le peut pas.
 * Deux étages en aval **conservent la référence** du tableau qu'on leur
 * donne — la réserve de pré-roll de TranscriptionEngine, et la file de
 * BufferedSpeechRecognizer. Leur passer un tampon réutilisé ferait écraser
 * sous eux du son qu'ils croient détenir, et le défaut serait invisible :
 * pas de plantage, juste du texte qui ne correspond plus à ce qui a été dit.
 *
 * Une copie subsiste donc, mais côté LECTURE — hors du fil temps réel — et
 * par blocs de cent millisecondes au lieu de dix. Le nombre d'allocations est
 * divisé par dix, et surtout aucune ne se produit plus là où elle coûte.
 *
 * ═══ Débordement : on écrase le plus ancien ═══
 *
 * Même arbitrage que partout ailleurs dans cette chaîne : si la transcription
 * ne suit pas, c'est elle qui perd du son, jamais le haut-parleur qui attend.
 *
 * @param capacityBytes taille du tampon. Doit dépasser largement un bloc.
 */
class PcmRingBuffer(capacityBytes: Int) {

    private val buffer = ByteArray(capacityBytes)
    private var writePos = 0
    private var readPos = 0
    private var availableBytes = 0
    private val lock = Object()

    /** Octets écrasés faute de place, cumulés. Lu pour le journal technique. */
    @Volatile var overwrittenBytes = 0L
        private set

    /**
     * Recopie [length] octets depuis [data], à partir de sa position courante.
     *
     * **La position de [data] est restaurée en sortant.** WebRTC réutilise ce
     * tampon et peut le présenter à d'autres consommateurs ; le consommer
     * modifierait ce qu'ils lisent. Restaurer la position coûte deux appels,
     * là où `duplicate()` aurait alloué un objet à chaque bloc — ce qui aurait
     * réintroduit, en plus petit, exactement ce qu'on retire ici.
     */
    fun write(data: ByteBuffer, length: Int) {
        if (length <= 0) return
        val startPosition = data.position()
        synchronized(lock) {
            // Un bloc plus grand que le tampon entier n'a pas de sens ici, mais
            // s'il arrivait, la version naïve rendrait availableBytes négatif
            // puis écrirait hors des bornes. On ne garde alors que la fin —
            // c'est-à-dire le son le plus récent, cohérent avec la règle de
            // débordement.
            if (length >= buffer.size) {
                val skipped = length - buffer.size
                data.position(startPosition + skipped)
                data.get(buffer, 0, buffer.size)
                writePos = 0
                readPos = 0
                availableBytes = buffer.size
                overwrittenBytes += skipped.toLong() + availableBytes
                lock.notifyAll()
                data.position(startPosition)
                return
            }

            val spaceLeft = buffer.size - availableBytes
            if (length > spaceLeft) {
                val overflow = length - spaceLeft
                readPos = (readPos + overflow) % buffer.size
                availableBytes -= overflow
                overwrittenBytes += overflow.toLong()
            }

            val firstPart = minOf(length, buffer.size - writePos)
            data.get(buffer, writePos, firstPart)
            val secondPart = length - firstPart
            if (secondPart > 0) data.get(buffer, 0, secondPart)

            writePos = (writePos + length) % buffer.size
            availableBytes += length
            lock.notifyAll()
        }
        data.position(startPosition)
    }

    /**
     * Lit au plus [requestedLength] octets dans [dest]. Attend qu'il y ait de
     * quoi lire, au plus [WAIT_MS] millisecondes. Rend le nombre d'octets lus,
     * zéro si rien n'est venu.
     *
     * L'attente est bornée, et c'est nécessaire : sans borne, un arrêt demandé
     * pendant un silence resterait suspendu jusqu'au bloc suivant, qui peut ne
     * jamais venir.
     *
     * La boucle `while` autour de l'attente n'est pas une précaution de style :
     * un fil peut être réveillé sans que personne l'ait notifié, et lire alors
     * un tampon vide.
     */
    fun read(dest: ByteArray, requestedLength: Int): Int {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (availableBytes == 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return 0
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }

            val lengthToRead = minOf(minOf(requestedLength, dest.size), availableBytes)
            val firstPart = minOf(lengthToRead, buffer.size - readPos)
            System.arraycopy(buffer, readPos, dest, 0, firstPart)
            val secondPart = lengthToRead - firstPart
            if (secondPart > 0) System.arraycopy(buffer, 0, dest, firstPart, secondPart)

            readPos = (readPos + lengthToRead) % buffer.size
            availableBytes -= lengthToRead
            return lengthToRead
        }
    }

    /** Vide le tampon. Appelé quand la source change : ce son-là n'a plus cours. */
    fun clear() {
        synchronized(lock) {
            writePos = 0
            readPos = 0
            availableBytes = 0
        }
    }

    private companion object {
        /** Attente maximale d'un bloc, pour qu'un arrêt ne reste jamais suspendu. */
        const val WAIT_MS = 200L
    }
}
