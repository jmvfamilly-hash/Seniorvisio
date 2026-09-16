package com.seniorvisio.recueil

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

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

    /** Un titre, et l'illustration qui l'accompagne quand le flux en donne une. */
    data class Titre(val texte: String, val vignette: String?)

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
                        }
                        !dansUnArticle -> Unit   // titre du flux lui-même : ignoré
                        nom == "title" -> texte = p.nextText().trim()
                        nom == "description" || nom == "summary" ->
                            description = p.nextText()

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

                    XmlPullParser.END_TAG -> if (nom == "item" || nom == "entry") {
                        dansUnArticle = false
                        val t = texte
                        if (!t.isNullOrBlank()) {
                            titres += Titre(t, enclosure ?: autreImage ?: imageDans(description))
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

    /** Dernier recours : l'image glissée dans le HTML du chapô. */
    private fun imageDans(description: String?): String? {
        if (description.isNullOrBlank()) return null
        return Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(description)?.groupValues?.get(1)
    }
}
