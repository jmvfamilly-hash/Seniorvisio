package com.vangoghtimeline.model

import kotlin.math.max
import kotlin.math.min

/** Sujet d'une œuvre, déduit de son titre. */
enum class Subject(val label: String) { PORTRAIT("Portrait"), LANDSCAPE("Paysage"), STILL_LIFE("Nature morte"), OTHER("Autre sujet") }

/** Technique, déduite de la mention de matière du musée. */
enum class Technique(val label: String) { OIL("Huile"), WATERCOLOR("Aquarelle"), INK("Encre"), DRAWING("Dessin"), OTHER("Autre technique") }

enum class ColorMode(val label: String) { COLOR("Couleur"), BLACK_WHITE("Noir et blanc") }

/** Couleurs nommées de l'analyse des pixels (palette volontairement petite : ce qu'on sait nommer sans ambiguïté). */
enum class NamedColor(val label: String, val rgb: Int) {
    BLACK("Noir", 0x15171A), WHITE("Blanc", 0xF2F0EA), GRAY("Gris", 0x8A8D91), RED("Rouge", 0xC23B32), ORANGE("Orange", 0xE08A2B),
    YELLOW("Jaune", 0xE6C63A), GREEN("Vert", 0x4E9A4E), BLUE("Bleu", 0x3C6FC4), PURPLE("Violet", 0x7B4FA8), PINK("Rose", 0xD878A8), BROWN("Brun", 0x7A5232),
}

/** Ce que disent les métadonnées (titre, technique) : disponible tout de suite, sans image. */
data class MetaTags(val subject: Subject, val technique: Technique)

/** Ce que disent les pixels de la vignette : couleur ou noir et blanc, et les couleurs dominantes (de la plus présente à la moins présente). */
data class PixelTags(val mode: ColorMode, val dominant: List<NamedColor>)

object MetaTagger {
    private fun has(text: String, words: List<String>) = words.any { it in text }

    private val portrait = listOf("portrait", "autoportrait", "zelfportret", "retrato", "ritratto", "bildnis", "head of", "tête de", "tete de", "kop van", "bust of", "buste")
    private val stillLife = listOf("still life", "still-life", "nature morte", "stilleven", "bodegón", "bodegon", "natura morta", "stillleben", "flowers", "fleurs", "bouquet", "vase", "fruit", "apples", "pommes", "sunflowers", "tournesols", "irises", "iris", "roses", "bloemen", "peonies", "pivoines", "poppies", "coquelicots")
    private val landscape = listOf(
        "landscape", "paysage", "landschap", "paisaje", "paesaggio", "landschaft", "view of", "view from", "vue de", "vue sur", "seascape", "marine", "coast", "côte", "cote ", "beach", "plage", "river", "rivière", "riviere", "bridge", "pont ", "forest", "forêt", "foret", "wood", "field", "champ", "garden", "jardin", "orchard", "verger", "harbor", "harbour", "port de", "haystack", "meule", "mountain", "montagne", "sunset", "coucher de soleil", "village", "street", "rue ", "road", "route", "church", "église", "eglise", "trees", "arbres", "pond", "étang", "etang", "canal", "sea", "mer ", "lake", "lac ", "poplars", "peupliers", "cliff", "falaise", "valley", "vallée", "vallee", "meadow", "prairie", "fields", "waterlilies", "water lilies", "nymphéas", "nympheas", "snow", "neige", "winter", "hiver", "spring", "printemps", "summer", "autumn",
    )

    fun subject(title: String): Subject {
        val t = title.lowercase()
        return when {
            has(t, portrait) -> Subject.PORTRAIT
            has(t, stillLife) -> Subject.STILL_LIFE
            has(t, landscape) -> Subject.LANDSCAPE
            else -> Subject.OTHER
        }
    }

    /** Premier terme reconnu, dans l'ordre : huile, aquarelle (gouache comprise), encre, dessin (graphite, craie, fusain, pastel…). */
    fun technique(medium: String?): Technique {
        val m = medium?.lowercase() ?: return Technique.OTHER
        return when {
            has(m, listOf("huile", "oil", "olieverf", "óleo", "oleo", "olio", "öl auf", "oil on")) -> Technique.OIL
            has(m, listOf("aquarelle", "watercolor", "watercolour", "waterverf", "acuarela", "acquerello", "aquarell", "gouache")) -> Technique.WATERCOLOR
            has(m, listOf("encre", "ink", "inkt", "tinta", "inchiostro", "tusche", "pen and", "à la plume")) -> Technique.INK
            has(m, listOf("dessin", "drawing", "crayon", "pencil", "graphite", "chalk", "craie", "charcoal", "fusain", "pastel", "tekening", "dibujo", "disegno", "zeichnung", "potlood", "sanguine", "red chalk")) -> Technique.DRAWING
            else -> Technique.OTHER
        }
    }

    fun tag(artwork: Artwork): MetaTags {
        val medium = artwork.details.firstOrNull { it.first == "Technique" }?.second ?: artwork.medium
        return MetaTags(subject(artwork.title), technique(medium))
    }
}

/** Analyse de la couleur d'une vignette réduite (pixels ARGB). Pure : testée sur la JVM ; l'image vient de Coil côté Android. */
object PixelTagger {
    /** Au-dessous de cette saturation moyenne (sur les pixels ni noirs ni blancs), l'image est en noir et blanc (sépia et papier jauni compris). */
    private const val BW_SATURATION = 0.12f

    fun nameOf(rgb: Int): NamedColor {
        val r = (rgb shr 16 and 255) / 255f
        val g = (rgb shr 8 and 255) / 255f
        val b = (rgb and 255) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val v = mx
        val s = if (mx == 0f) 0f else (mx - mn) / mx
        if (v < 0.16f) return NamedColor.BLACK
        if (s < 0.14f) return if (v > 0.86f) NamedColor.WHITE else NamedColor.GRAY
        val d = mx - mn
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        return when {
            (h < 45f && v < 0.58f && h >= 8f) -> NamedColor.BROWN
            h < 14f || h >= 345f -> if (s < 0.5f && v > 0.7f) NamedColor.PINK else NamedColor.RED
            h < 40f -> NamedColor.ORANGE
            h < 68f -> NamedColor.YELLOW
            h < 165f -> NamedColor.GREEN
            h < 262f -> NamedColor.BLUE
            h < 300f -> NamedColor.PURPLE
            else -> NamedColor.PINK
        }
    }

    /** [pixels] ARGB de la vignette réduite. Les couleurs dominantes : au plus 3, chacune au moins [minShare] des pixels. */
    fun analyze(pixels: IntArray, minShare: Float = 0.08f): PixelTags {
        if (pixels.isEmpty()) return PixelTags(ColorMode.COLOR, emptyList())
        val counts = IntArray(NamedColor.values().size)
        var satSum = 0f
        var satN = 0
        for (p in pixels) {
            counts[nameOf(p).ordinal]++
            val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val b = (p and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx in 0.16f..0.95f) { satSum += if (mx == 0f) 0f else (mx - mn) / mx; satN++ }
        }
        val meanSat = if (satN == 0) 0f else satSum / satN
        val mode = if (meanSat < BW_SATURATION) ColorMode.BLACK_WHITE else ColorMode.COLOR
        val dominant = NamedColor.values()
            .filter { counts[it.ordinal] >= minShare * pixels.size }
            // en couleur, les neutres (noir, blanc, gris) ne sont pas « la » couleur de l'œuvre : ils passent après les teintes
            .sortedWith(compareBy({ mode == ColorMode.COLOR && it in neutrals }, { -counts[it.ordinal] }))
            .take(3)
        return PixelTags(mode, dominant)
    }

    private val neutrals = setOf(NamedColor.BLACK, NamedColor.WHITE, NamedColor.GRAY)
}

/** La sélection de filtres de la frise : chaque critère vide = pas de filtre ; les critères choisis se cumulent (ET). */
data class TagFilter(
    val subject: Subject? = null,
    val technique: Technique? = null,
    val mode: ColorMode? = null,
    val hue: NamedColor? = null,
) {
    val active: Boolean get() = subject != null || technique != null || mode != null || hue != null
    val needsPixels: Boolean get() = mode != null || hue != null

    /** [pixel] `null` = vignette pas encore analysée : l'œuvre ne répond alors à aucun filtre de couleur. */
    fun matches(meta: MetaTags, pixel: PixelTags?): Boolean =
        (subject == null || meta.subject == subject) &&
            (technique == null || meta.technique == technique) &&
            (mode == null || pixel?.mode == mode) &&
            (hue == null || (pixel != null && hue in pixel.dominant))
}

/** Codage compact d'une analyse pour le fichier d'index : « c:rouge,bleu » → `C:RED,BLUE`. */
object PixelCodec {
    fun encode(t: PixelTags): String = (if (t.mode == ColorMode.COLOR) "C" else "B") + ":" + t.dominant.joinToString(",") { it.name }
    fun decode(s: String): PixelTags? {
        val mode = when (s.substringBefore(':')) { "C" -> ColorMode.COLOR; "B" -> ColorMode.BLACK_WHITE; else -> return null }
        val colors = s.substringAfter(':', "").split(',').filter { it.isNotEmpty() }.mapNotNull { n -> NamedColor.values().firstOrNull { it.name == n } }
        return PixelTags(mode, colors)
    }
}
