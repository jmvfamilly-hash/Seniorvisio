package com.seniorvisio.recueil

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Lit un fil d'information et n'en garde que ce que Jean verra.
 *
 * ═══ POURQUOI C'EST LA TABLETTE QUI CHARGE, ET PAS LE PWA ═══
 *
 * Un serveur RSS n'autorise pas les pages d'autres sites à le lire — la règle
 * des origines croisées, qui vaut pour tout navigateur. La tablette, elle, est
 * une application Android : elle n'y est pas soumise.
 *
 * Ce n'est pas un contournement, c'est le bon endroit. Les titres doivent de
 * toute façon vivre SUR la tablette pour être affichables hors ligne, comme
 * les photos d'un recueil (voir docs/architecture-recueils.md, § 5 : « les
 * deux aboutissent au même endroit, quelque chose que la tablette sait
 * afficher hors ligne »).
 *
 * ═══ CE QU'ON GARDE, ET CE QU'ON JETTE ═══
 *
 * Le titre et, s'il y en a une, l'adresse d'une vignette. Rien d'autre : ni
 * chapô, ni lien, ni date, ni catégorie. Un écran mural lu de loin par
 * quelqu'un de 88 ans ne porte pas un article, il porte une phrase.
 */
object FluxRss {

    private const val TAG = "FluxRss"

    /**
     * Un titre, son illustration quand le flux en donne une, et sa date de
     * publication.
     *
     * [date] est null quand le flux n'en donne pas, ou en donne une que ni le
     * format RSS ni le format Atom ne permettent de lire. Ce n'est pas un
     * détail : la sélection ne garde que les articles DU JOUR, et un article
     * sans date ne peut pas prouver qu'il en est. Il est donc écarté — mais le
     * nombre d'écartés est journalisé, pour qu'un flux entier qui disparaîtrait
     * faute de dates se voie au lieu de s'évaporer.
     */
    data class Titre(
        val texte: String,
        val vignette: String?,
        val date: Instant? = null,
        /**
         * Comment le fil se nomme lui-même, lu dans <channel><title>.
         *
         * Porté par chaque titre plutôt que rendu à part : la sélection mélange
         * les articles de plusieurs fils et les trie par date, donc à la sortie
         * plus rien ne dit de quel fil vient quoi. L'information doit voyager
         * AVEC l'article, ou elle est perdue au premier tri.
         */
        val origine: String? = null,
        /**
         * Le crédit du photographe, lu dans <media:credit>.
         *
         * Affiché SOUS LA PHOTO et non sous le titre, à la différence de
         * [origine] : il se rapporte à l'image, pas à l'article. Les mettre
         * ensemble laisserait croire que le fil s'appelle « LUDOVIC MARIN/AFP ».
         */
        val crédit: String? = null,
    )

    /**
     * Analyse un flux RSS ou Atom.
     *
     * Écrit contre les DEUX formats, et sans supposer l'ordre des balises :
     * un fil d'information peut changer de moteur de publication sans
     * prévenir, et une analyse qui suppose une forme précise casse ce jour-là
     * — un lundi matin, en silence, chez quelqu'un qui n'a aucun moyen de le
     * signaler.
     */
    fun analyser(entrée: InputStream, maximum: Int = 30): List<Titre> {
        val titres = mutableListOf<Titre>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(entrée, null)

            var dansUnArticle = false
            var texte: String? = null
            // Deux réservoirs, et pas un seul : l'enclosure L'EMPORTE, parce
            // que c'est là que ce flux-ci met son image. Avec une seule
            // variable prise par le premier arrivé, un media:thumbnail placé
            // avant dans le document gagnerait — et on afficherait une
            // miniature là où l'illustration existe.
            var enclosure: String? = null
            var autreImage: String? = null
            var description: String? = null
            var date: Instant? = null
            // L'illustration déclarée par <media:content>, et le crédit du
            // photographe qui l'accompagne. Séparés des deux réservoirs
            // ci-dessus parce qu'ils PASSENT DEVANT : quand le fil donne un
            // bloc média, c'est lui l'illustration de l'article.
            var mediaImage: String? = null
            var crédit: String? = null
            // Vrai entre <media:content> et sa fermeture. Les enfants de ce
            // bloc portent des noms — description, title, credit — que la
            // troncature du préfixe rend identiques à ceux de l'article.
            var dansMedia = false
            // Au niveau du CANAL, donc lu une fois et valable pour tous les
            // articles du fil. Déclaré ici et non dans la boucle : le
            // réinitialiser à chaque <item> l'effacerait, puisqu'il apparaît
            // avant le premier.
            var nomDuFil: String? = null

            var événement = p.eventType
            while (événement != XmlPullParser.END_DOCUMENT) {
                val nom = if (p.eventType == XmlPullParser.START_TAG ||
                    p.eventType == XmlPullParser.END_TAG
                ) p.name.substringAfterLast(':').lowercase() else ""

                when (événement) {
                    XmlPullParser.START_TAG -> when {
                        nom == "item" || nom == "entry" -> {
                            dansUnArticle = true
                            texte = null; description = null
                            enclosure = null; autreImage = null
                            date = null
                            mediaImage = null; crédit = null; dansMedia = false
                        }
                        // ═══ LE NOM DU FIL : <channel><title> ═══
                        //
                        // AVANT le garde ci-dessous, et c'est indispensable :
                        // ce titre-là est un élément du canal, pas d'un
                        // article. Placé après, il serait tombé dans la ligne
                        // « ignoré » qui suit et n'aurait jamais été lu.
                        //
                        // LE PREMIER SEULEMENT, et ce n'est pas une précaution
                        // de principe : RSS autorise <channel><image><title>,
                        // qui est le texte de remplacement du logo du site.
                        // Sans cette garde, ce libellé-là écraserait le nom du
                        // fil — et la mention affichée sous les titres de Jean
                        // deviendrait celle de l'image, sans que rien ne
                        // signale l'échange. Le titre du canal vient toujours
                        // avant son image et avant ses articles.
                        nom == "title" && !dansUnArticle && nomDuFil == null ->
                            nomDuFil = p.nextText().trim().takeIf { it.isNotBlank() }

                        !dansUnArticle -> Unit   // le reste de l'en-tête : ignoré

                        // ═══ LE BLOC MÉDIA EST TRAITÉ AVANT TOUT LE RESTE ═══
                        //
                        // Ces trois branches sont EN TÊTE, et l'ordre est le
                        // correctif lui-même : dans un « when », la première
                        // branche qui accepte gagne. Placées plus bas, elles
                        // arrivaient après « description », et la légende de la
                        // photo écrasait le chapô avant qu'elles ne soient
                        // seulement consultées.
                        //
                        // C'est la même faute que la garde du titre de canal,
                        // quelques lignes plus haut : un nom de balise abrégé
                        // ne dit pas à quel niveau il se trouve, et c'est la
                        // position dans la liste qui porte la distinction.
                        nom == "content" && !p.getAttributeValue(null, "url").isNullOrBlank() -> {
                            dansMedia = true
                            val url = p.getAttributeValue(null, "url")
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            val medium = p.getAttributeValue(null, "medium").orEmpty()
                            val estImage = (type.isBlank() || type.startsWith("image")) &&
                                (medium.isBlank() || medium == "image")
                            if (mediaImage == null && estImage) mediaImage = url
                        }

                        // Le crédit du photographe. Il n'a de sens que dans le
                        // bloc média : ailleurs, « credit » désignerait autre
                        // chose.
                        nom == "credit" && dansMedia ->
                            crédit = p.nextText().trim().takeIf { it.isNotBlank() }

                        // Le reste du bloc média est écarté, et <media:description>
                        // est la raison d'être de cette ligne : c'est la légende
                        // de la photo, pas le chapô de l'article. Vérifié en
                        // exécutant l'analyseur sur le flux réel — description
                        // valait « Bruno Retailleau, candidat Les Républicains… »
                        // au lieu du texte de l'article.
                        //
                        // Invisible à l'écran, puisque le chapô n'y est pas
                        // affiché ; visible dans imageDans(), le dernier recours
                        // qui cherche une image dans le HTML du chapô et
                        // fouillait la légende de l'image qu'on venait de ne pas
                        // prendre.
                        dansMedia -> Unit

                        nom == "title" -> texte = p.nextText().trim()
                        nom == "description" || nom == "summary" ->
                            description = p.nextText()

                        // RSS dit « pubDate », Atom dit « published » ou
                        // « updated ». Les trois sont acceptés, et « updated »
                        // en dernier recours seulement : un article corrigé à
                        // 18 h aurait sinon l'air d'être paru à 18 h.
                        nom == "pubdate" || nom == "date" ->
                            date = analyserDate(p.nextText())
                        nom == "published" -> date = analyserDate(p.nextText())
                        nom == "updated" -> if (date == null) date = analyserDate(p.nextText())

                        // ═══ L'IMAGE EST DANS L'ENCLOSURE ═══
                        //
                        // Confirmé par l'administrateur pour ce flux-ci. Le
                        // type n'est donc PAS exigé : plusieurs moteurs de
                        // publication omettent l'attribut, et le réclamer
                        // reviendrait à jeter l'image dans ces cas-là. On
                        // écarte en revanche ce qui s'annonce comme autre
                        // chose qu'une image — un fil peut joindre un son ou
                        // une vidéo à un article, et la tablette ne saurait
                        // pas l'afficher.
                        nom == "enclosure" -> {
                            val url = p.getAttributeValue(null, "url")
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            if (enclosure == null && !url.isNullOrBlank() &&
                                (type.isBlank() || type.startsWith("image"))
                            ) enclosure = url
                        }


                        // Replis, pour les flux qui font autrement. Gardés
                        // parce qu'ils ne coûtent rien et qu'un fil
                        // d'information change de moteur de publication sans
                        // prévenir — un lundi matin, chez quelqu'un qui n'a
                        // aucun moyen de le signaler.
                        nom == "thumbnail" || nom == "content" -> {
                            val url = p.getAttributeValue(null, "url")
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            if (autreImage == null && !url.isNullOrBlank() &&
                                (nom == "thumbnail" || type.startsWith("image"))
                            ) autreImage = url
                        }
                    }

                    XmlPullParser.END_TAG -> if (nom == "content") {
                        // La fermeture du bloc média rend leur sens ordinaire
                        // aux balises qui suivent, dans l'article.
                        dansMedia = false
                    } else if (nom == "item" || nom == "entry") {
                        dansUnArticle = false
                        val t = texte
                        if (!t.isNullOrBlank()) {
                            titres += Titre(
                                t,
                                mediaImage ?: enclosure ?: autreImage ?: imageDans(description),
                                date,
                                nomDuFil,
                                crédit,
                            )
                            if (titres.size >= maximum) return titres
                        }
                    }
                }
                événement = p.next()
            }
        } catch (e: Exception) {
            // Un flux mal formé ne doit pas emporter le reste : on garde ce
            // qui a été lu jusque-là. Quinze titres valent mieux que zéro.
            Log.w(TAG, "Flux interrompu après ${titres.size} titre(s)", e)
        }
        return titres
    }

    /**
     * La date de publication, quel que soit le format employé.
     *
     * ═══ DEUX FORMATS, ET AUCUN N'EST OPTIONNEL ═══
     *
     * RSS impose le format des courriels (RFC 822/1123) : « Tue, 16 Sep 2026
     * 18:30:00 +0200 ». Atom impose l'ISO 8601 : « 2026-09-16T18:30:00+02:00 ».
     * Un fil d'information change de moteur de publication sans prévenir, et
     * n'accepter qu'un des deux ferait disparaître tous ses articles du jour au
     * lendemain — sans erreur, sans trace, juste un écran qui ne montre plus
     * rien.
     *
     * Les deux sont donc essayés, et un échec rend null plutôt que de lever :
     * une date illisible fait perdre UN article, pas le flux entier.
     */
    private fun analyserDate(brut: String?): Instant? {
        val texte = brut?.trim().orEmpty()
        if (texte.isEmpty()) return null
        // RFC 1123 d'abord : c'est le format de RSS, donc le cas courant ici.
        runCatching {
            return ZonedDateTime.parse(texte, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }
        runCatching { return OffsetDateTime.parse(texte).toInstant() }
        runCatching { return Instant.parse(texte) }
        Log.w(TAG, "Date de publication illisible : « $texte »")
        return null
    }

    /** Dernier recours : l'image glissée dans le HTML du chapô. */
    private fun imageDans(description: String?): String? {
        if (description.isNullOrBlank()) return null
        return Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(description)?.groupValues?.get(1)
    }
}
