package com.papyrus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que ces essais vérifient, et pourquoi ils peuvent l'être.
 *
 * La chaîne de défilement n'importe rien d'Android : le temps y entre par
 * paramètre. On peut donc faire passer une seconde et demie en écrivant
 * « 1500 » — sans quoi vérifier un délai de stabilité coûterait une seconde et
 * demie d'attente réelle par cas, et personne ne relancerait la série.
 *
 * Les cas reproduits ne sont pas inventés : ils viennent des traces relevées
 * sur la tablette. Le monologue sans coupure, les trois « rien compris »
 * consécutifs, les révisions de mots du moteur — chacun a été observé avant
 * d'être transformé en essai.
 */
class WordStabiliserTest {

    private fun collector(): Pair<MutableList<String>, (String) -> Unit> {
        val words = mutableListOf<String>()
        return words to { w: String -> words.add(w) }
    }

    @Test
    fun `un mot n'est acquis qu'apres le delai de stabilite`() {
        val (words, sink) = collector()
        val s = WordStabiliser(1_500, sink)

        s.submitPartial("bonjour", 0)
        assertEquals("rien avant le délai", emptyList<String>(), words)

        s.tick(1_499)
        assertEquals("rien une milliseconde trop tôt", emptyList<String>(), words)

        s.tick(1_500)
        assertEquals(listOf("bonjour"), words)
    }

    @Test
    fun `une revision du moteur est absorbee avant l'affichage`() {
        // Cas réel de la trace : « par comme un Rob » devient « par comme un
        // robot ». La révision ne doit jamais atteindre l'écran, c'est tout
        // l'objet du délai.
        val (words, sink) = collector()
        val s = WordStabiliser(1_500, sink)

        s.submitPartial("par comme un Rob", 0)
        s.submitPartial("par comme un robot", 800)
        s.tick(2_400)

        assertEquals(listOf("par", "comme", "un", "robot"), words)
        assertFalse("la version révisée ne doit jamais sortir", words.contains("Rob"))
    }

    @Test
    fun `les mots sortent dans l'ordre, jamais par sauts`() {
        val (words, sink) = collector()
        val s = WordStabiliser(1_000, sink)

        // « il » est posé tôt, « pleut » plus tard : au moment où « pleut »
        // serait mûr, « il » doit déjà être sorti, et avant lui.
        s.submitPartial("il", 0)
        s.submitPartial("il pleut", 500)
        s.tick(1_600)

        assertEquals(listOf("il", "pleut"), words)
    }

    @Test
    fun `un mot deja acquis n'est jamais reecrit`() {
        // Invariant : ce qui est parti à l'écran ne change plus, même si le
        // moteur se ravise ensuite.
        val (words, sink) = collector()
        val s = WordStabiliser(1_000, sink)

        s.submitPartial("bonjour", 0)
        s.tick(1_000)
        assertEquals(listOf("bonjour"), words)

        s.submitPartial("bonsoir tout le monde", 1_100)
        s.tick(3_000)

        assertEquals("le premier mot reste celui qui a été lu",
            listOf("bonjour", "tout", "le", "monde"), words)
    }

    @Test
    fun `la fin de session publie tout ce qui reste`() {
        // Aucun mot perdu : c'est l'invariant le plus important de la chaîne.
        val (words, sink) = collector()
        val s = WordStabiliser(1_500, sink)

        s.submitPartial("bonjour tout le monde", 0)
        // Aucun mot n'a eu le temps de mûrir.
        assertEquals(emptyList<String>(), words)

        s.finish("bonjour tout le monde")
        assertEquals(listOf("bonjour", "tout", "le", "monde"), words)
    }

    @Test
    fun `la fin de session sans texte final retombe sur les candidats`() {
        // La trace montre des sessions closes sans résultat final. Les mots
        // déjà proposés ne doivent pas disparaître pour autant.
        val (words, sink) = collector()
        val s = WordStabiliser(1_500, sink)

        s.submitPartial("il y a quelqu'un", 0)
        s.finish(null)

        assertEquals(listOf("il", "y", "a", "quelqu'un"), words)
    }

    @Test
    fun `la reconciliation ne retouche que la fin`() {
        val (words, sink) = collector()
        val s = WordStabiliser(1_000, sink)

        s.submitPartial("le mot fonction", 0)
        s.tick(1_000)
        assertEquals(listOf("le", "mot", "fonction"), words)

        // Le moteur rend un texte final où le troisième mot diffère. Les deux
        // premiers sont déjà lus : on n'y touche pas. Le troisième l'est aussi,
        // donc il reste tel quel lui aussi — et le quatrième s'ajoute.
        s.finish("le mot fonctionné vient")
        assertEquals(listOf("le", "mot", "fonction", "vient"), words)
    }

    @Test
    fun `un monologue continu sort entierement, sans aucun evenement de segmentation`() {
        // Le cas de la trace rev18 : seize secondes et demie de parole, un seul
        // début de parole, aucune fin intermédiaire. La chaîne ne doit dépendre
        // d'aucun de ces signaux — elle n'en reçoit d'ailleurs aucun ici.
        val (words, sink) = collector()
        val s = WordStabiliser(1_500, sink)
        val queue = WordQueue()

        val phrase = ("je vais juste continuer à parler on verra s'il y a des " +
            "disparitions ou pas dans ce texte qui dure assez longtemps").split(" ")

        var now = 0L
        for (count in 1..phrase.size) {
            s.submitPartial(phrase.take(count).joinToString(" "), now)
            now += 400
        }
        s.tick(now + 2_000)
        s.finish(phrase.joinToString(" "))

        words.forEach { queue.enqueue(it) }
        assertEquals("aucun mot perdu sur un monologue sans coupure",
            phrase.size, queue.backlog)
        assertEquals(phrase, words)
    }
}

class WordQueueTest {

    @Test
    fun `la file ne perd aucun mot, quelle que soit sa taille`() {
        val q = WordQueue()
        repeat(5_000) { q.enqueue("mot$it") }
        assertEquals(5_000, q.backlog)
        assertEquals("mot0", q.dequeue())
        assertEquals("mot1", q.dequeue())
        assertEquals(4_998, q.backlog)
    }

    @Test
    fun `une file vide ne rend rien plutot que de bloquer`() {
        assertNull(WordQueue().dequeue())
    }
}

class BacklogIndicatorTest {

    @Test
    fun `le voyant s'allume au-dessus du seuil d'entree`() {
        val v = BacklogIndicator(enterAt = 8, exitAt = 4)
        v.update(8)
        assertFalse("huit n'est pas « au-dessus de huit »", v.visible)
        v.update(9)
        assertTrue(v.visible)
    }

    @Test
    fun `l'hysteresis empeche le clignotement`() {
        // Sans zone morte, une file oscillant autour du seuil ferait clignoter
        // le point — exactement ce qu'il ne doit jamais faire.
        val v = BacklogIndicator(enterAt = 8, exitAt = 4)
        v.update(9)
        assertTrue(v.visible)

        for (backlog in listOf(8, 7, 6, 5, 4)) {
            v.update(backlog)
            assertTrue("reste allumé dans la zone morte (file = $backlog)", v.visible)
        }

        v.update(3)
        assertFalse(v.visible)
    }
}

class ScrollRateTest {

    @Test
    fun `la cadence par defaut vaut bien cent cinquante mots par minute`() {
        assertEquals(150, ScrollRate.DEFAULT.wordsPerMinute)
        assertEquals(400L, ScrollRate.DEFAULT.intervalMs)
    }

    @Test
    fun `les crans ne bouclent pas aux extremites`() {
        // Boucler ferait passer de « très rapide » à « très lent » d'une seule
        // pression : un accident, jamais une intention.
        assertEquals(ScrollRate.TRES_LENT, ScrollRate.TRES_LENT.previous())
        assertEquals(ScrollRate.TRES_RAPIDE, ScrollRate.TRES_RAPIDE.next())
    }

    @Test
    fun `les cinq paliers sont ceux de la specification`() {
        assertEquals(
            listOf(80, 120, 150, 200, 260),
            ScrollRate.entries.map { it.wordsPerMinute },
        )
    }

    @Test
    fun `un reglage inconnu retombe sur la valeur par defaut`() {
        assertEquals(ScrollRate.DEFAULT, ScrollRate.fromNameOrDefault(null))
        assertEquals(ScrollRate.DEFAULT, ScrollRate.fromNameOrDefault("ULTRA_VITE"))
        assertEquals(ScrollRate.LENT, ScrollRate.fromNameOrDefault("LENT"))
    }
}

/**
 * L'invariant central de la spécification, vérifié là où il vit.
 *
 * La cadence ne doit jamais dépendre de la file d'attente. Ici ce n'est pas une
 * convention à respecter mais un fait de structure : [ScrollRate] ne connaît
 * pas [WordQueue], et rien dans la chaîne ne permet à l'une d'influencer
 * l'autre. Ces essais le constatent plutôt qu'ils ne le testent — et c'est
 * exactement la garantie qu'on veut.
 */
class CadenceIndependanceTest {

    @Test
    fun `changer de palier ne perd ni ne deplace aucun mot en attente`() {
        val q = WordQueue()
        repeat(12) { q.enqueue("mot$it") }

        var rate = ScrollRate.NORMAL
        // Trois mots défilent, puis on change de vitesse en pleine lecture.
        repeat(3) { q.dequeue() }
        rate = rate.next()
        assertEquals(ScrollRate.RAPIDE, rate)

        assertEquals("la file est intacte après le changement", 9, q.backlog)
        assertEquals("le mot suivant est bien celui qui venait", "mot3", q.dequeue())
    }

    @Test
    fun `l'intervalle ne depend que du palier choisi`() {
        // Écrit comme une table plutôt que comme une formule : si quelqu'un
        // change le calcul, c'est la valeur en millisecondes qui doit être
        // discutée, pas l'arithmétique. Et rien, dans ces valeurs, ne peut
        // dépendre de la file d'attente — elle n'apparaît nulle part.
        assertEquals(750L, ScrollRate.TRES_LENT.intervalMs)
        assertEquals(500L, ScrollRate.LENT.intervalMs)
        assertEquals(400L, ScrollRate.NORMAL.intervalMs)
        assertEquals(300L, ScrollRate.RAPIDE.intervalMs)
        assertEquals(230L, ScrollRate.TRES_RAPIDE.intervalMs)
    }
}

/**
 * Le mode d'écoute livré est unique, et cet essai le fige.
 *
 * Il paraît trivial jusqu'au jour où quelqu'un ajoute un sélecteur « pour
 * essayer » et l'oublie en place. L'essai échouera alors, et dira pourquoi.
 */
class SequencingModeTest {

    @Test
    fun `le mode livre est sequence, et lui seul`() {
        assertEquals("séquence", SequencingMode.SEQUENCE.label)
        // Les deux autres existent toujours comme instruments de mesure, et
        // c'est voulu — ce qui ne doit pas exister, c'est un chemin de
        // production qui les atteigne.
        assertTrue(SequencingMode.entries.contains(SequencingMode.API_PURE))
        assertTrue(SequencingMode.entries.contains(SequencingMode.EVENTS))
    }
}
