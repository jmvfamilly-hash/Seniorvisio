package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCalculatorTest {
    private val info = IiifImageInfo("https://h/iiif/img", 4000, 3000, 256, listOf(1, 2, 4, 8, 16))

    @Test fun idealFactorPicksSharpLevel() {
        assertEquals(1, TileCalculator.idealScaleFactor(1f, info))
        assertEquals(2, TileCalculator.idealScaleFactor(0.5f, info))
        assertEquals(4, TileCalculator.idealScaleFactor(0.25f, info))
        assertEquals(2, TileCalculator.idealScaleFactor(0.3f, info)) // 1/0.3 = 3.3 -> 2, jamais 4 (flou)
        assertEquals(16, TileCalculator.idealScaleFactor(0.06f, info))
        assertEquals(1, TileCalculator.idealScaleFactor(2f, info))   // au-delà de 100 % : pleine résolution
    }

    @Test fun wholeImageAtFitScale() {
        val tiles = TileCalculator.calculateVisibleTiles(ViewportState(0.25f, 0f, 0f), info, 1000, 750)
        assertEquals(12, tiles.size) // 4 colonnes x 3 lignes de 1024 px image
        assertTrue(tiles.all { it.scaleFactor == 4 })
    }

    @Test fun edgeTilesAreTruncatedToImage() {
        val tiles = TileCalculator.calculateVisibleTiles(ViewportState(0.25f, 0f, 0f), info, 1000, 750)
        val last = tiles.first { it.column == 3 && it.row == 2 }
        assertEquals(IiifRegion(3072, 2048, 928, 952), last.region)
        assertEquals(232, last.outputWidth)
        assertEquals(238, last.outputHeight)
    }

    @Test fun zoomedViewportSelectsOnlyIntersectingTiles() {
        val tiles = TileCalculator.calculateVisibleTiles(ViewportState(1f, -1000f, -500f), info, 512, 512)
        assertEquals(9, tiles.size)
        assertEquals(3..5, tiles.map { it.column }.toSortedSet().let { it.first()..it.last() })
        assertEquals(1..3, tiles.map { it.row }.toSortedSet().let { it.first()..it.last() })
    }

    @Test fun boundaryExactlyOnGridLineAddsNoExtraColumn() {
        val tiles = TileCalculator.calculateVisibleTiles(ViewportState(1f, -1024f, 0f), info, 512, 256)
        assertEquals(setOf(4, 5), tiles.map { it.column }.toSet())
        assertEquals(setOf(0), tiles.map { it.row }.toSet())
    }

    @Test fun imageOutsideScreenGivesNoTiles() {
        assertTrue(TileCalculator.calculateVisibleTiles(ViewportState(1f, 2000f, 0f), info, 512, 512).isEmpty())
    }

    @Test fun coarsestLevelIsASingleTile() {
        val all = TileCalculator.allTiles(info, 16)
        assertEquals(1, all.size)
        assertEquals(IiifRegion(0, 0, 4000, 3000), all[0].region)
        assertEquals(188, all[0].outputHeight) // 3000 / 16 = 187.5 arrondi au supérieur
    }

    @Test fun urlFollowsIiifImageApi() {
        val tile = TileCalculator.allTiles(info, 4).first { it.column == 3 && it.row == 2 }
        assertEquals("4/3/2", tile.id)
        assertEquals("https://h/iiif/img/3072,2048,928,952/232,/0/default.jpg", IiifUrls.tile(info, tile))
    }
}
