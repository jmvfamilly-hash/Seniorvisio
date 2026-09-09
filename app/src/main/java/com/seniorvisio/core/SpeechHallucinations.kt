package com.seniorvisio.core

import java.text.Normalizer

/**
 * Reconnaît les formules qu'un moteur de transcription invente quand il n'a
 * rien entendu.
 *
 * Ce n'est pas un défaut de notre chaîne audio. Les modèles de la famille
 * Whisper — sur lesquels Gladia s'appuie — ont été entraînés sur des
 * sous-titres de vidéos, génériques de fin compris. Nourris de silence ou de
 * bruit de fond, ils rendent malgré tout du texte, et c'est celui qui terminait
 * ces vidéos : « Merci. », « Merci d'avoir regardé cette vidéo », « Sous-titres
 * réalisés par… ». En français, « Merci. » est de très loin le plus fréquent.
 *
 * La règle, et c'est tout l'enjeu : un énoncé n'est écarté que si sa
 * **totalité** est une de ces formules. « merci » est un mot que Jean et ses
 * proches disent réellement, plusieurs fois par appel. Le retirer partout
 * amputerait de vraies phrases — « merci mon grand », « au revoir et merci » —
 * et Jean n'aurait aucun moyen de savoir qu'il lui manque un mot. Une
 * hallucination en trop se remarque et s'ignore ; une parole effacée, non.
 *
 * Câblé sur Gladia seulement (voir GladiaStreamingTranscriber). Les trois
 * autres moteurs ne sont pas concernés : AssemblyAI en v3 n'est pas un modèle
 * Whisper, Vosk et la reconnaissance d'Android ne fabriquent pas de texte sur
 * du silence — ils se taisent. Appliquer le filtre à tout le monde reviendrait
 * à faire courir le risque d'effacer un « merci » là où il n'y a rien à gagner.
 */
object SpeechHallucinations {

    /**
     * Vrai si [text] n'est **que** des formules d'hallucination, éventuellement
     * répétées ou enchaînées — le modèle en produit volontiers deux ou trois de
     * suite sur un long silence.
     */
    fun isPureHallucination(text: String): Boolean {
        val normalised = normalise(text)
        if (normalised.isEmpty()) return false
        return decomposesIntoKnownPhrases(normalised)
    }

    /**
     * Ramène un texte à une forme comparable : minuscules, sans accents, sans
     * ponctuation, mots séparés par une seule espace.
     *
     * Les formules de référence passent par la même fonction (voir [phrases]),
     * ce qui évite d'avoir à deviner comment le service ponctue, accentue ou
     * apostrophe — « d'avoir » et « d’avoir » se ramènent au même, et un
     * « Merci !!! » aussi.
     */
    private fun normalise(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        var pendingSpace = false
        decomposed.forEach { character ->
            // Les accents sont, après décomposition, des caractères à part
            // entière de cette catégorie. Testés ainsi plutôt que par une
            // classe d'expression régulière : celles qui désignent les accents
            // n'ont pas toujours été fiables selon les versions d'Android.
            if (Character.getType(character) == Character.NON_SPACING_MARK.toInt()) return@forEach
            if (character.isLetterOrDigit()) {
                if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
                pendingSpace = false
                builder.append(character.lowercaseChar())
            } else {
                pendingSpace = true
            }
        }
        return builder.toString()
    }

    /**
     * Le texte peut-il se découper entièrement en formules connues ?
     *
     * Un simple test d'appartenance ne suffirait pas : « Merci. Merci
     * beaucoup. » est une hallucination aussi sûrement que « Merci. » seul, et
     * c'est une forme que le modèle produit couramment. On cherche donc un
     * découpage complet, en s'assurant que chaque formule s'arrête sur une
     * frontière de mot — sans quoi « merci » validerait le début de
     * « mercier ».
     */
    private fun decomposesIntoKnownPhrases(normalised: String): Boolean {
        val reachable = BooleanArray(normalised.length + 1)
        reachable[0] = true
        for (start in normalised.indices) {
            if (!reachable[start]) continue
            phrases.forEach { phrase ->
                if (!normalised.startsWith(phrase, start)) return@forEach
                val end = start + phrase.length
                when {
                    end == normalised.length -> reachable[end] = true
                    normalised[end] == ' ' -> reachable[end + 1] = true
                }
            }
        }
        return reachable[normalised.length]
    }

    /**
     * Les formules, telles qu'on les écrirait. Elles sont normalisées une fois
     * au chargement plutôt que saisies déjà normalisées : une liste illisible
     * finit par contenir une faute que personne ne voit.
     *
     * Toutes viennent des génériques de fin de vidéos, sauf les deux premières.
     * Celles-là sont un choix assumé : « merci » et « merci beaucoup » peuvent
     * être de vraies paroles. Elles sont écartées quand même parce qu'elles
     * arrivent des dizaines de fois par heure sur du silence, alors qu'un
     * remerciement réellement prononcé l'est presque toujours à l'intérieur
     * d'une phrase — laquelle passe intacte.
     */
    private val phrases: List<String> = listOf(
        "Merci.",
        "Merci beaucoup.",
        "Merci à tous.",
        "Merci à tous et à bientôt.",
        "Merci de votre attention.",
        "Merci d'avoir regardé cette vidéo.",
        "Merci d'avoir regardé cette vidéo !",
        "Merci d'avoir regardé.",
        // Volontairement absents de cette liste, bien que le modèle les
        // hallucine lui aussi : « Au revoir » et « À bientôt ». Ce sont les
        // mots par lesquels un proche termine réellement son appel, et les
        // effacer priverait Jean de l'au revoir qui lui était adressé. Le coût
        // d'une erreur n'est pas le même dans les deux sens.
        "Abonnez-vous !",
        "N'oubliez pas de vous abonner !",
        "Sous-titres réalisés par la communauté d'Amara.org",
        "Sous-titres réalisés para la communauté d'Amara.org",
        "Sous-titrage Société Radio-Canada",
        "Sous-titrage ST' 501",
        "❤️ par SousTitreur.com",
        "Générique",
        "Musique",
    ).map(::normalise).filter { it.isNotEmpty() }
        // Les plus longues d'abord : sans effet sur le résultat, qui explore
        // tous les découpages, mais on tombe plus vite sur le bon.
        .sortedByDescending { it.length }
}
