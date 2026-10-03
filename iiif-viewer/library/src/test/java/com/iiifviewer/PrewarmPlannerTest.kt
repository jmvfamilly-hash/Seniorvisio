package com.iiifviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrewarmPlannerTest {
    private val info = IiifImageInfo("https://h/iiif/img", 4032, 3200, 256, listOf(1, 2, 4, 8, 16))
    private val big = 64L * 1024 * 1024

    @Test fun fitViewportCentersTheWholeImage() {
        val vp = PrewarmPlanner.fitViewport(info, 1080, 2340)
        assertEquals(1080f / 4032f, vp.scale, 1e-5f)               // limité par la largeur
        assertEquals(0f, vp.translationX, 1e-3f)
        assertEquals((2340 - 3200 * vp.scale) / 2f, vp.translationY, 1e-2f)
    }

    @Test fun coarsestLevelComesFirstThenTheSharpLevelFromTheCenter() {
        val plan = PrewarmPlanner.plan(info, 1080, 2340, big)
        val base = info.scaleFactors.last()
        val baseCount = TileCalculator.allTiles(info, base).size
        assertTrue(plan.take(baseCount).all { it.scaleFactor == base })
        val arrival = plan.drop(baseCount)
        assertTrue(arrival.isNotEmpty())
        assertEquals(2, arrival.first().scaleFactor)                 // 1/0,268 = 3,7 -> niveau 2
        assertEquals(plan.size, plan.distinctBy { it.id }.size)      // aucun doublon
    }

    @Test fun arrivalTilesCoverTheWholeImageAtFit() {
        val plan = PrewarmPlanner.plan(info, 1080, 2340, big).filter { it.scaleFactor == 2 }
        val span = 256 * 2
        assertEquals(((4032 + span - 1) / span) * ((3200 + span - 1) / span), plan.size)
    }

    @Test fun smallBudgetFallsBackToACoarserLevel() {
        val sharp = PrewarmPlanner.plan(info, 1080, 2340, big).maxOf { 1.0 / it.scaleFactor }
        val soft = PrewarmPlanner.plan(info, 1080, 2340, 2L * 1024 * 1024).maxOf { 1.0 / it.scaleFactor }
        assertTrue(soft < sharp)
    }

    @Test fun noScreenMeansNothingToLoad() {
        assertTrue(PrewarmPlanner.plan(info, 0, 100, big).isEmpty())
    }

    @Test fun tinyImageIsJustItsCoarsestLevel() {
        val tiny = IiifImageInfo("https://h/i", 200, 100, 256, listOf(1))
        val plan = PrewarmPlanner.plan(tiny, 1080, 2340, big)
        assertEquals(1, plan.size)
    }
}
