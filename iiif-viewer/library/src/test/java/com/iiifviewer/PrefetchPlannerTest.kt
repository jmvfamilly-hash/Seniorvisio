package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPlannerTest {
    private val info = IiifImageInfo("https://h/iiif/img", 6000, 4000, 256, listOf(1, 2, 4, 8, 16))
    // zoom 0.5 -> niveau idéal 2 ; l'écran montre 1640 x 2360 px image
    private val vp = ViewportState(0.5f, -500f, -300f)
    private val sw = 820
    private val sh = 1180

    private fun plan(m: ViewMotion = ViewMotion(), v: ViewportState = vp) = PrefetchPlanner.plan(v, info, sw, sh, m)
    private fun List<PlannedTile>.prio(p: Int) = filter { it.priority == p }

    @Test fun visibleTilesArePriorityZeroAtIdealLevel() {
        val visible = TileCalculator.calculateVisibleTiles(vp, info, sw, sh)
        val p0 = plan().prio(0)
        assertEquals(visible.map { it.id }.toSet(), p0.map { it.tile.id }.toSet())
        assertTrue(p0.all { it.tile.scaleFactor == 2 })
    }

    @Test fun parentLevelIsPriorityOne() {
        val p1 = plan().prio(1)
        assertTrue(p1.isNotEmpty())
        assertTrue(p1.all { it.tile.scaleFactor == 4 })
    }

    @Test fun tileIdsAreUnique() {
        val ids = plan().map { it.tile.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun marginAddsTilesAroundTheScreen() {
        val p0 = plan().prio(0).size
        val p2 = plan().prio(2).size
        assertTrue("la marge doit ajouter des tuiles", p2 > 0)
        assertTrue(p0 > 0)
    }

    @Test fun panLooksAheadInTheDirectionOfMotion() {
        // contenu qui file vers la gauche (vx < 0) : on regarde vers la droite
        val still = plan(ViewMotion(vx = 0f)).filter { it.priority <= 2 }
        val moving = plan(ViewMotion(vx = -900f)).filter { it.priority <= 2 }
        assertTrue(moving.maxOf { it.tile.column } > still.maxOf { it.tile.column })
        assertEquals(still.minOf { it.tile.column }, moving.minOf { it.tile.column })
    }

    @Test fun zoomingInPreparesFinerLevelAroundAnchorOnly() {
        val m = ViewMotion(vz = 1.0f, anchorX = 410f, anchorY = 590f)
        val p = plan(m)
        assertTrue(p.prio(3).isNotEmpty())
        assertTrue(p.prio(3).all { it.tile.scaleFactor == 1 })
        assertTrue(p.prio(4).isEmpty())
    }

    @Test fun zoomingOutPreparesCoarserLevels() {
        val p = plan(ViewMotion(vz = -1.0f))
        assertTrue(p.prio(4).isNotEmpty())
        assertTrue(p.prio(4).all { it.tile.scaleFactor >= 4 })
        assertTrue(p.prio(3).isEmpty())
    }

    @Test fun idlePreparesBothDirections() {
        val p = plan(ViewMotion())
        assertTrue(p.prio(3).isNotEmpty())
        assertTrue(p.prio(4).isNotEmpty())
    }

    @Test fun fastPanSkipsZoomPreparation() {
        val p = plan(ViewMotion(vx = -1500f))
        assertTrue(p.prio(3).isEmpty())
        assertTrue(p.prio(4).isEmpty())
    }

    @Test fun nothingFinerThanFullResolution() {
        val full = ViewportState(1.2f, -300f, -200f)   // niveau idéal 1 : le plus fin
        assertFalse(plan(v = full).any { it.priority == 3 })
    }

    @Test fun coarsestLevelHasNoParent() {
        val fit = ViewportState(0.1f, 0f, 0f)          // 1/0.1 = 10 -> niveau 8 ; en dessous : 16
        val coarsest = ViewportState(0.05f, 0f, 0f)    // 1/0.05 = 20 -> niveau 16 (le plus grossier)
        assertTrue(plan(v = fit).prio(1).isNotEmpty())
        assertTrue(plan(v = coarsest).prio(1).isEmpty())
    }

    @Test fun tileInRectMatchesVisibleTilesForFullScreen() {
        val a = TileCalculator.calculateTilesAtLevel(vp, info, sw, sh, 2).map { it.id }
        val b = TileCalculator.calculateTilesInRect(vp, info, 0f, 0f, sw.toFloat(), sh.toFloat(), 2).map { it.id }
        assertEquals(a, b)
    }
}
