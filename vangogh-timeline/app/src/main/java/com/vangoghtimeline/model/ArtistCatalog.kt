package com.vangoghtimeline.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lecture du catalogue `artists_by_movement` (JSON) puis enrichissement par [ArtistExtras].
 * Pur Kotlin : testé sur la JVM. Un artiste mal formé est ignoré, jamais fatal.
 */
object ArtistCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Artist> {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return emptyList()
        val byMovement = root["artists_by_movement"] as? JsonObject ?: return emptyList()
        val out = ArrayList<Artist>()
        for (movement in Movement.values()) {
            val group = byMovement[movement.key] as? JsonObject ?: continue
            for (el in (group["artists"] as? JsonArray).orEmpty()) {
                val o = el as? JsonObject ?: continue
                val name = o.str("name") ?: continue
                val period = o.str("active_period").orEmpty()
                val (start, end) = parsePeriod(period)
                val id = slugOf(name)
                val extras = ArtistExtras.of(id)
                out += Artist(
                    id = id, name = name, origin = o.str("origin").orEmpty(), activePeriod = period,
                    activeStart = start, activeEnd = end,
                    mainStyle = o.str("main_style").orEmpty(), emblematicWork = o.str("emblematic_work").orEmpty(),
                    workType = o.str("work_type").orEmpty(),
                    locations = (o["key_locations"] as? JsonArray).orEmpty().mapNotNull { l ->
                        val lo = l as? JsonObject ?: return@mapNotNull null
                        KeyLocation(lo.str("location") ?: return@mapNotNull null, lo.str("work_type").orEmpty(), lo.str("presence_period").orEmpty())
                    },
                    movement = movement,
                    birthYear = extras?.birth, deathYear = extras?.death,
                    wikipediaTitle = extras?.wikipedia,
                    // la source Wikimedia a besoin du titre de l'article Wikipédia de l'artiste
                    sources = extras?.sources.orEmpty().map { if (it.sourceId == "wikimedia" && it.term == null) it.copy(term = extras?.wikipedia) else it },
                )
            }
        }
        return out
    }

    /** « 1860s-1926 » → 1860..1926 ; « 1870s-1910s » → 1870..1919 (une décennie finit à son dernier millésime) ; « 1840-1877 ». */
    fun parsePeriod(text: String): Pair<Int?, Int?> {
        val m = Regex("""(\d{4})(s?)\s*-\s*(\d{4})(s?)""").find(text) ?: return null to null
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[3].toInt() + if (m.groupValues[4].isNotEmpty()) 9 else 0
        return start to end
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/**
 * Ce que le catalogue ne dit pas : dates de vie, article Wikipédia (portrait) et **sources d'œuvres** par artiste.
 *
 * C'est ici qu'on ouvre un univers, par étapes : un artiste sans source reste grisé. Les sources sont validées au moment de
 * la connexion (voir `SourceValidator`) : en ajouter une ici ne l'expose à l'utilisateur que si son accès IIIF est vérifié.
 */
object ArtistExtras {
    class Extras(val birth: Int, val death: Int, val wikipedia: String, val sources: List<SourceSpec> = emptyList())

    private fun s(id: String, term: String? = null) = SourceSpec(id, term)

    /**
     * Toutes les sources, pour un artiste qui a un univers. L'ORDRE compte : les musées directs d'abord ; les agrégateurs (Europeana, Wikimedia) en dernier,
     * pour que, à titre et année égaux, l'œuvre vienne du musée lui-même ; la sonde de reconnaissance (CER.ES) n'ajoute aucune œuvre.
     *
     * @param rijks nom du créateur au format du Rijksmuseum (« Nom, Prénom »)
     * @param europeanaProvider fournisseur de données Europeana propre à l'artiste (ex. « Museo Sorolla »), si connu
     * @param ceres vrai pour un artiste des musées d'État espagnols : ajoute la sonde de reconnaissance CER.ES
     */
    private fun allSources(rijks: String, europeanaProvider: String? = null, ceres: Boolean = false) = listOf(
        s("aic"), s("rijks", rijks), s("cleveland"), s("met"), s("smk"), s("nga"),
        s("europeana", europeanaProvider), s("wikimedia"),
    ) + if (ceres) listOf(s("ceres")) else emptyList()

    private val all: Map<String, Extras> = mapOf(
        "gustave-courbet" to Extras(1819, 1877, "Gustave_Courbet"),
        "camille-corot" to Extras(1796, 1875, "Jean-Baptiste-Camille_Corot"),
        "j-m-w-turner" to Extras(1775, 1851, "J._M._W._Turner"),
        "giovanni-fattori" to Extras(1825, 1908, "Giovanni_Fattori"),
        "winslow-homer" to Extras(1836, 1910, "Winslow_Homer"),
        "claude-monet" to Extras(1840, 1926, "Claude_Monet", allSources("Monet, Claude")),
        "pierre-auguste-renoir" to Extras(1841, 1919, "Pierre-Auguste_Renoir", allSources("Renoir, Pierre Auguste")),
        "berthe-morisot" to Extras(1841, 1895, "Berthe_Morisot", allSources("Morisot, Berthe")),
        "mary-cassatt" to Extras(1844, 1926, "Mary_Cassatt"),
        "eva-gonzales" to Extras(1849, 1883, "Eva_Gonzal%C3%A8s"),
        "louise-catherine-breslau" to Extras(1856, 1927, "Louise_Catherine_Breslau"),
        "joaquin-sorolla" to Extras(1863, 1923, "Joaqu%C3%ADn_Sorolla", allSources("Sorolla y Bastida, Joaquín", "Museo Sorolla", ceres = true)),
        "john-singer-sargent" to Extras(1856, 1925, "John_Singer_Sargent", allSources("Sargent, John Singer")),
        "camille-pissarro" to Extras(1830, 1903, "Camille_Pissarro"),
        "vincent-van-gogh" to Extras(1853, 1890, "Vincent_van_Gogh", allSources("Gogh, Vincent van")),
        "paul-cezanne" to Extras(1839, 1906, "Paul_C%C3%A9zanne"),
        "edvard-munch" to Extras(1863, 1944, "Edvard_Munch"),
        "anna-boch" to Extras(1848, 1936, "Anna_Boch"),
        "georges-seurat" to Extras(1859, 1891, "Georges_Seurat"),
        "paul-gauguin" to Extras(1848, 1903, "Paul_Gauguin", allSources("Gauguin, Paul")),
    )

    fun of(id: String): Extras? = all[id]
}
