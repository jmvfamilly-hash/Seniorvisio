package com.seniorvisio.core

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Enveloppe un moteur de reconnaissance pour que le son n'y entre jamais
 * depuis le fil qui l'a capté.
 *
 * Le problème qu'elle résout ne se voit pas dans un test : les blocs de son
 * arrivent sur un fil temps réel — celui de la capture micro, ou celui de
 * WebRTC — dont le seul travail est de livrer le bloc suivant à l'heure. Tout
 * ce qu'on y fait retarde le bloc d'après. Or [accept] y faisait jusqu'ici
 * deux choses coûteuses : ré-échantillonner le PCM vers 16 kHz mono, et
 * remettre les octets à une pile réseau. Un hoquet de connexion, une pile
 * réseau qui bloque une poignée de millisecondes, et c'est la capture qui
 * prend du retard — au mieux du son haché, au pire des blocs perdus.
 *
 * D'où cette file d'attente entre les deux, et un seul fil pour la vider. Le
 * fil audio ne fait plus que déposer un bloc et repartir.
 *
 * Volontairement une enveloppe et non une correction dans chaque moteur : les
 * trois ont le même problème, et la logique d'AssemblyAI a déjà divergé une
 * fois d'avoir été recopiée à deux endroits. Un seul exemplaire, appliqué à
 * tous par TranscriptionEngine.
 *
 * File **bornée**, et c'est essentiel : une file sans limite ne perd rien
 * mais grossit indéfiniment quand le moteur n'arrive plus à suivre — jusqu'à
 * faire tomber l'application sur une tablette allumée en permanence, plusieurs
 * heures plus tard, sans que rien ne relie la panne à sa cause. Mieux vaut
 * perdre quelques dixièmes de seconde de son et le dire.
 *
 * Quand elle déborde, c'est le bloc le PLUS ANCIEN qui part. Le texte affiché
 * chez Jean est déjà volontairement en retard sur la parole pour qu'il ait le
 * temps de lire ; y ajouter un retard subi, qui ne se rattraperait jamais,
 * serait le pire des deux mondes. On préfère un trou dans le passé à un
 * décalage permanent.
 */
class BufferedSpeechRecognizer(
    private val delegate: SpeechRecognizer,
    private val onDiagnostic: (String) -> Unit = {},
) : SpeechRecognizer {

    /** Celui du moteur enveloppé : la file n'est pas un moteur, elle en cache un. */
    override val engine = delegate.engine

    private class Block(val pcm16: ByteArray, val sampleRate: Int, val channels: Int)

    private val queue = ArrayBlockingQueue<Block>(MAX_QUEUED_BLOCKS)
    private var worker: Thread? = null
    @Volatile private var running = false

    /** Blocs jetés faute de place, pour le dire une fois plutôt qu'à chaque fois. */
    private var droppedBlocks = 0
    private var reportedDrop = false

    override fun start(onText: (text: String, isFinal: Boolean) -> Unit, onError: (String) -> Unit) {
        delegate.start(onText, onError)
        running = true
        worker = Thread {
            while (running) {
                // Attente bornée plutôt que bloquante : sans elle, un arrêt
                // demandé pendant un silence resterait suspendu jusqu'au bloc
                // suivant, qui peut ne jamais venir.
                val block = try {
                    queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                try {
                    delegate.accept(block.pcm16, block.sampleRate, block.channels)
                } catch (e: Exception) {
                    // Un moteur qui échoue sur un bloc ne doit pas emporter le
                    // fil qui vide la file : le suivant a toutes les chances de
                    // passer, et s'arrêter ici rendrait la transcription muette
                    // pour de bon.
                    Log.w(TAG, "Bloc refusé par le moteur", e)
                }
            }
        }.apply {
            name = "SeniorVisio-Transcription"
            // Sous la priorité du fil audio : ce fil-ci peut attendre, celui
            // qui capte le son ne le peut pas.
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    override fun accept(pcm16: ByteArray, sampleRate: Int, channels: Int) {
        if (!running) return
        val block = Block(pcm16, sampleRate, channels)
        if (queue.offer(block)) return
        // Plein : on fait de la place en jetant le plus ancien, puis on
        // dépose. Si la course avec le fil de vidage fait échouer les deux, le
        // bloc courant est perdu — sans conséquence, c'est déjà le cas de
        // celui qu'on voulait jeter.
        queue.poll()
        queue.offer(block)
        droppedBlocks++
        if (!reportedDrop) {
            reportedDrop = true
            onDiagnostic("transcription en retard : du son est perdu")
            Log.w(TAG, "File de transcription pleine, blocs les plus anciens jetés")
        }
    }

    override fun stop() {
        running = false
        worker?.interrupt()
        // Attente courte : on laisse au moteur une chance de finir le bloc en
        // cours, sans bloquer l'appelant — stop() est appelé depuis le fil
        // principal, à la fin d'un appel ou d'un changement de moteur.
        try {
            worker?.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        queue.clear()
        if (droppedBlocks > 0) {
            Log.i(TAG, "$droppedBlocks blocs de son jetés pendant cette session")
            droppedBlocks = 0
        }
        reportedDrop = false
        delegate.stop()
    }

    private companion object {
        const val TAG = "BufferedSpeechRecognizer"

        /**
         * Environ deux secondes de son aux tailles de blocs observées. Assez
         * pour absorber un hoquet réseau, assez peu pour que le retard reste
         * imperceptible si la file se remplit.
         */
        const val MAX_QUEUED_BLOCKS = 64

        const val POLL_TIMEOUT_MS = 200L
        const val JOIN_TIMEOUT_MS = 500L
    }
}
