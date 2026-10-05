package com.vangoghtimeline

import com.vangoghtimeline.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtTagsTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun subjectFromTitleInSeveralLanguages() {
        assertEquals(Subject.PORTRAIT, MetaTagger.subject("Portrait of Madame X"))
        assertEquals(Subject.PORTRAIT, MetaTagger.subject("Zelfportret met grijze vilthoed"))
        assertEquals(Subject.PORTRAIT, MetaTagger.subject("Autoportrait au chapeau de paille"))
        assertEquals(Subject.STILL_LIFE, MetaTagger.subject("Nature morte aux pommes"))
        assertEquals(Subject.STILL_LIFE, MetaTagger.subject("Vase with Sunflowers"))
        assertEquals(Subject.LANDSCAPE, MetaTagger.subject("Wheat Field with Cypresses"))
        assertEquals(Subject.LANDSCAPE, MetaTagger.subject("Vue de Rouen, le pont"))
        assertEquals(Subject.OTHER, MetaTagger.subject("Les Joueurs de cartes"))
        // un portrait dans un jardin reste un portrait ; des fleurs dans un paysage restent une nature morte
        assertEquals(Subject.PORTRAIT, MetaTagger.subject("Portrait of a Lady in the garden"))
        assertEquals(Subject.STILL_LIFE, MetaTagger.subject("Bouquet near the river"))
    }

    @Test fun techniqueFromTheMedium() {
        assertEquals(Technique.OIL, MetaTagger.technique("Oil on canvas"))
        assertEquals(Technique.OIL, MetaTagger.technique("Huile sur toile"))
        assertEquals(Technique.OIL, MetaTagger.technique("olieverf op doek"))
        assertEquals(Technique.WATERCOLOR, MetaTagger.technique("Watercolor and gouache over graphite"))
        assertEquals(Technique.INK, MetaTagger.technique("Pen and brown ink"))
        assertEquals(Technique.DRAWING, MetaTagger.technique("Black chalk on paper"))
        assertEquals(Technique.DRAWING, MetaTagger.technique("pastel"))
        assertEquals(Technique.OTHER, MetaTagger.technique("Bronze"))
        assertEquals(Technique.OTHER, MetaTagger.technique(null))
    }

    @Test fun pureGrayImageIsBlackAndWhiteAndColorfulImageIsColor() {
        val gray = IntArray(256) { px(it, it, it) }
        assertEquals(ColorMode.BLACK_WHITE, PixelTagger.analyze(gray).mode)
        val sepia = IntArray(256) { px(120 + it / 8, 116 + it / 8, 110 + it / 8) }          // papier à peine teinté ; un sépia franc (saturation > 12 %) compte comme couleur
        assertEquals(ColorMode.BLACK_WHITE, PixelTagger.analyze(sepia).mode)
        val colorful = IntArray(256) { if (it % 2 == 0) px(200, 40, 30) else px(40, 80, 200) }
        assertEquals(ColorMode.COLOR, PixelTagger.analyze(colorful).mode)
    }

    @Test fun colorNamesAndDominantOrder() {
        assertEquals(NamedColor.RED, PixelTagger.nameOf(px(200, 40, 30)))
        assertEquals(NamedColor.BLUE, PixelTagger.nameOf(px(40, 80, 200)))
        assertEquals(NamedColor.GREEN, PixelTagger.nameOf(px(60, 160, 70)))
        assertEquals(NamedColor.YELLOW, PixelTagger.nameOf(px(230, 200, 60)))
        assertEquals(NamedColor.BROWN, PixelTagger.nameOf(px(120, 80, 45)))
        assertEquals(NamedColor.BLACK, PixelTagger.nameOf(px(10, 10, 12)))
        assertEquals(NamedColor.WHITE, PixelTagger.nameOf(px(250, 248, 245)))
        assertEquals(NamedColor.GRAY, PixelTagger.nameOf(px(130, 130, 132)))
        // 60 % bleu, 30 % rouge, 10 % noir : les teintes d'abord, par présence ; le noir (neutre) en dernier
        val img = IntArray(100) { when { it < 60 -> px(40, 80, 200); it < 90 -> px(200, 40, 30); else -> px(5, 5, 5) } }
        assertEquals(listOf(NamedColor.BLUE, NamedColor.RED, NamedColor.BLACK), PixelTagger.analyze(img).dominant)
        assertTrue(PixelTagger.analyze(IntArray(0)).dominant.isEmpty())
    }

    @Test fun filterCombinesCriteriaAndNeedsPixelsForColor() {
        val meta = MetaTags(Subject.PORTRAIT, Technique.OIL)
        val pix = PixelTags(ColorMode.COLOR, listOf(NamedColor.RED, NamedColor.BROWN))
        assertTrue(TagFilter().matches(meta, null))
        assertTrue(TagFilter(subject = Subject.PORTRAIT, technique = Technique.OIL).matches(meta, null))
        assertTrue(!TagFilter(subject = Subject.LANDSCAPE).matches(meta, pix))
        assertTrue(!TagFilter(mode = ColorMode.COLOR).matches(meta, null))      // pas encore analysée : exclue
        assertTrue(TagFilter(mode = ColorMode.COLOR, hue = NamedColor.RED).matches(meta, pix))
        assertTrue(!TagFilter(hue = NamedColor.BLUE).matches(meta, pix))
        assertTrue(TagFilter(hue = NamedColor.BLUE).needsPixels && !TagFilter(subject = Subject.PORTRAIT).needsPixels)
    }

    @Test fun codecRoundTripAndRejectsGarbage() {
        val t = PixelTags(ColorMode.BLACK_WHITE, listOf(NamedColor.GRAY, NamedColor.WHITE))
        assertEquals(t, PixelCodec.decode(PixelCodec.encode(t)))
        assertEquals(PixelTags(ColorMode.COLOR, emptyList()), PixelCodec.decode("C:"))
        assertNull(PixelCodec.decode("zzz"))
    }

    @Test fun layoutOfAnEmptyFilteredListIsEmptyAndDoesNotCrash() {
        val plan = TimelineEngine.layout(emptyList(), 1.6f, CardSpec(100f, 80f, 5f, 5f), 40f)
        assertTrue(plan.items.isEmpty())
    }

    private fun art(service: String?, w: Int?, h: Int?, rights: RightsInfo? = null, manifest: String = "") =
        Artwork("a", "t", ArtworkDate.year(1888), iiif = IiifRef(manifest, imageServiceId = service, canvasWidth = w, canvasHeight = h), rights = rights)

    @Test fun definitionTierFromImageSize() {
        assertEquals(DefinitionTier.WOW, DefinitionTier.of(art("https://x/iiif/1", 21000, 28000)))
        assertEquals(DefinitionTier.SIMPLE, DefinitionTier.of(art("https://x/iiif/1", 2400, 3000)))
        assertEquals(DefinitionTier.NONE, DefinitionTier.of(art("https://x/iiif/1", 800, 600)))
        assertEquals(DefinitionTier.SIMPLE, DefinitionTier.of(art("https://x/iiif/1", null, null)))     // taille inconnue
        assertEquals(DefinitionTier.NONE, DefinitionTier.of(art(null, null, null)))                    // rien à ouvrir
        // image restreinte : la résolution servie l'emporte sur la taille de l'original
        val restricted = RightsCatalog.viewOnly("Image à accès restreint (usage loyal) — résolution limitée à 640 px — National Gallery of Art", "https://www.nga.gov/legal.html")
        assertEquals(DefinitionTier.NONE, DefinitionTier.of(art("https://x/iiif/1", 21000, 28000, restricted)))
    }

    @Test fun filterByRightsAndDefinition() {
        val meta = MetaTags(Subject.PORTRAIT, Technique.OIL)
        assertTrue(TagFilter(rights = RightsKind.PUBLIC_DOMAIN).matches(meta, null, RightsKind.PUBLIC_DOMAIN, DefinitionTier.WOW))
        assertTrue(!TagFilter(rights = RightsKind.PUBLIC_DOMAIN).matches(meta, null, RightsKind.VIEW_ONLY, DefinitionTier.WOW))
        assertTrue(TagFilter(rights = RightsKind.UNKNOWN).matches(meta, null, null, DefinitionTier.WOW))       // droits absents = non précisés
        assertTrue(TagFilter(definition = DefinitionTier.WOW).matches(meta, null, null, DefinitionTier.WOW))
        assertTrue(!TagFilter(definition = DefinitionTier.WOW).matches(meta, null, null, DefinitionTier.SIMPLE))
        assertTrue(TagFilter(definition = DefinitionTier.NONE).active)
    }
}
